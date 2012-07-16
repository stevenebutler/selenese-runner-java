package jp.vmi.selenium.selenese;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang.time.FastDateFormat;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverCommandProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.vmi.selenium.selenese.command.Command;
import jp.vmi.selenium.selenese.command.Command.Result;
import jp.vmi.selenium.utils.LoggerUtils;
import jp.vmi.selenium.webdriver.ChromeDriverFactory;
import jp.vmi.selenium.webdriver.DriverOptions;
import jp.vmi.selenium.webdriver.FirefoxDriverFactory;
import jp.vmi.selenium.webdriver.HtmlUnitDriverFactory;
import jp.vmi.selenium.webdriver.IEDriverFactory;
import jp.vmi.selenium.webdriver.SafariDriverFactory;
import jp.vmi.selenium.webdriver.WebDriverFactory;

import static java.lang.System.*;

public class Runner {

    private static final Logger log = LoggerFactory.getLogger(Runner.class);

    private static final Comparator<Cookie> cookieComparator = new Comparator<Cookie>() {
        @Override
        public int compare(Cookie c1, Cookie c2) {
            return c1.getName().compareTo(c2.getName());
        }
    };

    private static final FastDateFormat expiryFormat = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss");

    private final WebDriver driver;
    private static Options options = null;

    private File screenshotDir = null;
    private boolean screenshotAll = false;

    private List<String> prevMessages = new ArrayList<String>();

    public Runner(WebDriver driver) {
        this.driver = driver;
    }

    public Runner(WebDriverFactory wdf) {
        this(wdf.get());
    }

    public File getScreenshotDir() {
        return screenshotDir;
    }

    public void setScreenshotDir(File screenshotDir) {
        this.screenshotDir = screenshotDir;
    }

    public boolean isScreenshotAll() {
        return screenshotAll;
    }

    public void setScreenshotAll(boolean screenshotAll) {
        this.screenshotAll = screenshotAll;
    }

    private void cookieToMessage(List<String> messages) {
        List<Cookie> cookieList = new ArrayList<Cookie>(driver.manage().getCookies());
        Collections.sort(cookieList, cookieComparator);
        for (Cookie cookie : cookieList) {
            Date expiry = cookie.getExpiry();
            String expiryString = expiry != null ? expiryFormat.format(expiry) : "*";
            messages.add(String.format("- Cookie: %s=[%s] (domain=%s, path=%s, expire=%s)", cookie.getName(), cookie.getValue(),
                cookie.getDomain(), cookie.getPath(), expiryString));
        }
    }

    public void run(Context context, Command current) {
        while (current != null) {
            log.info(current.toString());
            Result result = current.doCommand(context);

            log(result);

            takeScreenshot(current.getIndex());

            current = current.next(context);
        }
    }

    private void log(Result result) {
        List<String> messages = new ArrayList<String>();
        messages.add(String.format("URL: [%s] / Title: [%s]", driver.getCurrentUrl(), driver.getTitle()));
        cookieToMessage(messages);
        if (ListUtils.isEqualList(messages, prevMessages)) {
            log.info("- {}", result);
        } else {
            Iterator<String> iter = messages.iterator();
            log.info("- {} {}", result, iter.next());
            while (iter.hasNext())
                log.info(iter.next());
            prevMessages = messages;
        }
    }

    private void takeScreenshot(int index) {
        FastDateFormat fsf = FastDateFormat.getInstance("yyyyMMddHHmmssSSS");
        if (screenshotAll) {
            if (!(driver instanceof TakesScreenshot)) {
                throw new UnsupportedOperationException("webdriver is not support taking screenshot.");
            }
            TakesScreenshot screenshottaker = (TakesScreenshot) driver;
            File tmp = screenshottaker.getScreenshotAs(OutputType.FILE);
            String datetime = fsf.format(Calendar.getInstance().getTime());
            tmp.renameTo(new File(screenshotDir, "capture_" + datetime + "_" + index + ".png"));
            log.info(tmp.getAbsolutePath());
        }
    }

    public void run(String file) {
        long stime = System.nanoTime();
        String name = file.replaceFirst(".*[/\\\\]", "");
        try {
            log.info("Start: {}", name);
            Parser parser = new Parser(file);
            WebDriverCommandProcessor proc = new WebDriverCommandProcessor(parser.getBaseURI(), driver);
            Context context = new Context(proc);
            run(context, parser.parse(proc, context));
        } catch (RuntimeException e) {
            log.error(e.getMessage());
            throw e;
        } finally {
            log.info("End({}): {}", LoggerUtils.durationToString(stime, System.nanoTime()), name);
        }
    }

    /**
     * Selenese script runner.
     *
     * @param args options and filenames
     */
    public static void main(String[] args) {
        CommandLine cli = null;
        try {
            cli = new PosixParser().parse(getOptions(), args);
            for (Option opt : cli.getOptions()) {
                log.debug(opt.getLongOpt() + ":" + opt.getValue());
            }
        } catch (ParseException e) {
            help("Error: " + e.getMessage());
        }
        if (cli.getArgs().length == 0)
            help();
        DriverOptions driverOptions = new DriverOptions(cli);

        WebDriverFactory wdf;
        String driverName = cli.getOptionValue("driver");
        try {
            wdf = getWebDriverFactory(driverOptions, driverName);
        } catch (InvalidConfigurationException e) {
            help("Error: " + e.getMessage());
            return; // not reached.
        } catch (NoSuchWebDriverException e) {
            help("Error: Driver does not exist: " + driverName);
            return; // not reached.
        }

        Runner runner = new Runner(wdf);
        runner.setScreenshotDir(new File(cli.getOptionValue("screenshot-dir", new File(".").getAbsoluteFile().getParent())));
        runner.setScreenshotAll(cli.hasOption("screenshot-all"));

        for (String arg : cli.getArgs()) {
            if (!new File(arg).exists()) {
                help("Error: file not exists \"" + arg + "\"");
            }
        }

        for (String arg : cli.getArgs()) {
            runner.run(arg);
        }
        exit(0);
    }

    protected static WebDriverFactory getWebDriverFactory(DriverOptions driverOptions, String driverName)
        throws InvalidConfigurationException, NoSuchWebDriverException {
        WebDriverFactory wdf;
        if (driverName == null || "firefox".equalsIgnoreCase(driverName)) {
            wdf = WebDriverFactory.getFactory(FirefoxDriverFactory.class, driverOptions);
        } else if ("chrome".equalsIgnoreCase(driverName)) {
            wdf = WebDriverFactory.getFactory(ChromeDriverFactory.class, driverOptions);
        } else if ("ie".equalsIgnoreCase(driverName)) {
            wdf = WebDriverFactory.getFactory(IEDriverFactory.class, driverOptions);
        } else if ("safari".equalsIgnoreCase(driverName)) {
            wdf = WebDriverFactory.getFactory(SafariDriverFactory.class, driverOptions);
        } else if ("htmlunit".equalsIgnoreCase(driverName)) {
            wdf = WebDriverFactory.getFactory(HtmlUnitDriverFactory.class, driverOptions);
        } else {
            try {
                wdf = WebDriverFactory.getFactory(driverName, driverOptions);
            } catch (ClassNotFoundException e) {
                throw new NoSuchWebDriverException("No such WebDriverException:" + driverName);
            }
        }
        return wdf;
    }

    private static void help(String... msgs) {
        if (msgs.length > 0) {
            for (String msg : msgs)
                System.err.println(msg);
            System.err.println();
        }
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java -jar selenese-runner.jar <SELENESE_FILE> ...", "", getOptions(), FOOTER);
        exit(1);
    }

    private static final String FOOTER = "\n"
        + "Run selenese script file from command line.\n"
        + "*note: If you want to use proxy authentication on Firefox, "
        + "then create new profile, "
        + "configure all settings, "
        + "install AutoAuth plugin, "
        + "and specify the profile by --profile option.";

    @SuppressWarnings("static-access")
    private static synchronized Options getOptions() {
        if (options == null) {
            options = new Options();
            options.addOption(OptionBuilder.withLongOpt("driver")
                .withDescription("firefox (default) | chrome | ie | safari | htmlunit | FQCN of web driver factory.").hasArg()
                .withArgName("DRIVER")
                .create());
            options.addOption(OptionBuilder.withLongOpt("profile")
                .withDescription("profile name (Firefox only)")
                .withArgName("PROFILE")
                .hasArg()
                .create());
            options.addOption(OptionBuilder.withLongOpt("profile-dir")
                .withDescription("profile directory (Firefox only)")
                .withArgName("PROFILE_DIR")
                .hasArg()
                .create());
            options.addOption(OptionBuilder.withLongOpt("proxy")
                .withDescription("proxy host and port (HOST:PORT)")
                .withArgName("PROXY")
                .hasArg()
                .create());
            options.addOption(OptionBuilder.withLongOpt("proxy-user")
                .withDescription("proxy username (HtmlUnit only *)")
                .withArgName("PROXY_USER")
                .hasArg()
                .create());
            options.addOption(OptionBuilder.withLongOpt("proxy-password")
                .withDescription("proxy password (HtmlUnit only *)")
                .withArgName("PROXY_PASSWORD")
                .hasArg()
                .create());
            options.addOption(OptionBuilder.withLongOpt("no-proxy")
                .withDescription("no proxy")
                .withArgName("NO_PROXY")
                .hasArg()
                .create());
            options.addOption(OptionBuilder.withLongOpt("screenshot-dir")
                .withDescription("directory for screenshot images. (default: current directory)").hasArg()
                .create());
            options.addOption(OptionBuilder.withLongOpt("screenshot-all")
                .withDescription("screenshot all commands")
                .create());
        }
        return options;
    }
}