package com.sudothought.common;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.io.CharStreams;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigSyntax;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class Utils {

  private static final Logger logger = LoggerFactory.getLogger(Utils.class);

  private Utils() {
  }

  public static String getBanner(final String filename) {
    try (final InputStream in = logger.getClass().getClassLoader().getResourceAsStream(filename)) {
      final String banner = CharStreams.toString(new InputStreamReader(in, Charsets.UTF_8.name()));
      final List<String> lines = Splitter.on("\n").splitToList(banner);

      // Use Atomic values because filter requires finals
      // Trim initial and trailing blank lines, but preserve blank lines in middle;
      final AtomicInteger first = new AtomicInteger(-1);
      final AtomicInteger last = new AtomicInteger(-1);
      final AtomicInteger lineNum = new AtomicInteger(0);
      lines.forEach(
          line -> {
            if (line.trim().length() > 0) {
              if (first.get() == -1)
                first.set(lineNum.get());
              last.set(lineNum.get());
            }
            lineNum.incrementAndGet();
          });

      lineNum.set(0);
      final String noNulls =
          Joiner.on("\n")
                .skipNulls()
                .join(
                    lines.stream()
                         .filter(
                             input -> {
                               final int currLine = lineNum.getAndIncrement();
                               return currLine >= first.get() && currLine <= last.get();
                             })
                         .map(input -> format("     %s", input))
                         .collect(Collectors.toList()));
      return format("%n%n%s%n%n", noNulls);
    }
    catch (Throwable e) {
      return format("Banner %s cannot be found", filename);
    }
  }

  public static Config readConfig(final String cliConfig, final String envConfig, final boolean exitOnMissingConfig) {
    return readConfig(cliConfig,
                      envConfig,
                      ConfigParseOptions.defaults().setAllowMissing(false),
                      ConfigFactory.load().resolve(), exitOnMissingConfig).resolve(ConfigResolveOptions.defaults());
  }

  public static Config readConfig(final String cliConfig,
                                  final String envConfig,
                                  final ConfigParseOptions configParseOptions,
                                  final Config fallback,
                                  final boolean exitOnMissingConfig) {
    // Precedence of confg settings: CLI, ENV_VAR, config info
    final String configName = cliConfig != null ? cliConfig : System.getenv(envConfig);

    if (configName == null) {
      if (exitOnMissingConfig) {
        System.err.println(format("A configuration file or url must be specified with --config or $%s", envConfig));
        System.exit(1);
      }
      else {
        return fallback;
      }
    }

    final String lcname = configName.toLowerCase();

    if (lcname.startsWith("http://") || lcname.startsWith("https://")) {
      final ConfigSyntax configSyntax;
      if (lcname.endsWith(".json") || lcname.endsWith(".jsn"))
        configSyntax = ConfigSyntax.JSON;
      else if (lcname.endsWith(".properties") || lcname.endsWith(".props"))
        configSyntax = ConfigSyntax.PROPERTIES;
      else
        configSyntax = ConfigSyntax.CONF;

      try {
        return ConfigFactory.parseURL(new URL(configName), configParseOptions.setSyntax(configSyntax))
                            .withFallback(fallback);
      }
      catch (Exception e) {
        if (e.getCause() instanceof FileNotFoundException)
          logger.error("Invalid config url: {}", configName);
        else
          logger.error(e.getMessage(), e);
        System.exit(1);
      }
    }
    else {
      try {
        return ConfigFactory.parseFileAnySyntax(new File(configName), configParseOptions)
                            .withFallback(fallback);
      }
      catch (Exception e) {
        if (e.getCause() instanceof FileNotFoundException)
          logger.error("Invalid config filename: {}", configName);
        else
          logger.error(e.getMessage(), e);
        System.exit(1);
      }
    }
    // Never reached
    return fallback;
  }

  public static Optional<Integer> getEnvInt(final String varName, final boolean exitOnException) {
    final String val = System.getenv(varName);
    try {
      return Optional.of(Integer.parseInt(val));
    }
    catch (Throwable e) {
      logger.error("Invaid value for {}: {}", varName, val);
      if (exitOnException)
        System.exit(1);
    }
    return Optional.empty();
  }

  public static String getHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
      //final String address = InetAddress.getLocalHost().getHostAddress();
    }
    catch (UnknownHostException e) {
      return "Unknown";
    }
  }

  public static void sleepForMillis(final long millis) {
    try {
      Thread.sleep(millis);
    }
    catch (InterruptedException e) {
      logger.warn("Thread interrupted", e);
    }
  }

  public static void sleepForSecs(final long secs) {
    try {
      Thread.sleep(toMillis(secs));
    }
    catch (InterruptedException e) {
      logger.warn("Thread interrupted", e);
    }
  }

  public static long toMillis(final long secs) {
    return secs * 1000;
  }

  public static long toSecs(final long millis) {
    return millis / 1000;
  }
}
