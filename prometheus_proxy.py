import argparse
import logging
import socket
import time
from concurrent import futures
from queue import Queue
from threading import Thread, Lock, Event

import grpc
import requests
from flask import Flask, Response
from prometheus_client import start_http_server, Counter

from constants import GRPC_PORT_DEFAULT, PORT, LOG_LEVEL, PROXY_PORT_DEFAULT, GRPC
from proto.proxy_service_pb2 import ProxyServiceServicer, ScrapeRequest
from proto.proxy_service_pb2 import RegisterResponse
from proto.proxy_service_pb2 import add_ProxyServiceServicer_to_server
from utils import setup_logging, run

logger = logging.getLogger(__name__)

REQUEST_COUNTER = Counter('getDistances_request_type_count', 'getDistances() request type count', ['target'])


class PrometheusProxy(ProxyServiceServicer):
    def __init__(self, grpc_port, http_port):
        self.hostname = "[::]:{0}".format(grpc_port if grpc_port else GRPC_PORT_DEFAULT)
        self.http_port = http_port
        self.stopped = False
        self.grpc_server = None
        self.agent_count = 0
        self.agent_lock = Lock()
        self.request_queue = Queue()
        self.scrape_id = 0
        self.scrape_lock = Lock()
        self.request_dict = {}

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.stop()
        return self

    def start(self):
        run(target=self.run_grpc_server)

    def stop(self):
        if not self.stopped:
            logger.info("Stopping proxy")
            self.stopped = True
            self.grpc_server.stop(0)
        return self

    def run_grpc_server(self):
        logger.info("Starting gRPC service listening on %s", self.hostname)
        self.grpc_server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
        add_ProxyServiceServicer_to_server(self, self.grpc_server)
        self.grpc_server.add_insecure_port(self.hostname)
        self.grpc_server.start()

    def registerAgent(self, request, context):
        with self.agent_lock:
            self.agent_count += 1
            print("Registering agent {0} {1}".format(request.hostname, request.target_path))
            return RegisterResponse(agent_id=self.agent_count,
                                    proxy_url="http://{0}:{1}/{2}".format(socket.gethostname(),
                                                                          self.http_port,
                                                                          request.target_path))

    def submit_name(self, name):
        print("Submitting: " + name)
        with self.scrape_lock:
            self.scrape_id += 1
            req = ScrapeRequest(scrape_id=self.scrape_id, name=name)
            request_entry = RequestEntry(req)
            self.request_dict[self.scrape_id] = request_entry
        self.request_queue.put(req)
        return request_entry

    def readRequestsFromProxy(self, request, context):
        while True:
            req = self.request_queue.get()
            print("Processing: " + req.name)
            self.request_queue.task_done()
            yield req

    def writeResponsesToProxy(self, request_iterator, context):
        for result in request_iterator:
            request_entry = self.request_dict[result.scrape_id]
            request_entry.result = result
            request_entry.ready.set()


class RequestEntry(object):
    def __init__(self, request):
        self.request = request
        self.ready = Event()
        self.result = None


if __name__ == "__main__":
    setup_logging()

    parser = argparse.ArgumentParser()
    parser.add_argument("-p", "--port", dest=PORT, type=int, default=PROXY_PORT_DEFAULT, help="Proxy listening port")
    parser.add_argument("-g", "--grpc", dest=GRPC, type=int, default=GRPC_PORT_DEFAULT, help="gRPC listening port")
    parser.add_argument("-v", "--verbose", dest=LOG_LEVEL, default=logging.INFO, action="store_const",
                        const=logging.DEBUG, help="Enable debugging info")
    args = vars(parser.parse_args())

    setup_logging(level=args[LOG_LEVEL])

    with PrometheusProxy(args[GRPC], args[PORT]) as proxy:
        http = Flask(__name__)


        @http.route("/<name>")
        def target_request(name):
            if name == "metrics":
                f = requests.get("http://localhost:8001/metrics")
                return Response(f.text, mimetype='text/plain')
            else:
                request_entry = proxy.submit_name(name)
                request_entry.ready.wait()
                return Response(request_entry.result.text, mimetype='text/plain')

        # Run HTTP server in a thread
        thread = Thread(target=http.run, kwargs={"port": args[PORT]})
        thread.setDaemon(True)
        thread.start()

        # Start up a server to expose the metrics.
        start_http_server(8001)

        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass
