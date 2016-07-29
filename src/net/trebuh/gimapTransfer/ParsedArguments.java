package net.trebuh.gimapTransfer;

import java.io.PrintStream;
import java.io.PrintWriter;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class ParsedArguments {
    private final Options cliOptions;
    public final String googleUser;
    public final String targetHost;
    public final int targetPort;
    public final String targetUser;
    public final String targetFolerName;
    public final boolean useSSL;
    public final boolean useStartTLS;
    public final int googlePoolSize;
    public final int targetPoolSize;

    ParsedArguments(final String[] args) {
        cliOptions = new Options();
        cliOptions.addOption(Option.builder("h").longOpt("help").desc("Show this help message and exit").build());
        cliOptions.addOption(Option.builder("g").longOpt("google-user").required().hasArg().argName("userName")
                .desc("Set the GMail user name").build());
        cliOptions.addOption(Option.builder("t").longOpt("host").required().hasArg().argName("targetHost")
                .desc("Set the target IMAP server").build());
        cliOptions.addOption(Option.builder("p").longOpt("port").hasArg().argName("port")
                .desc("Set the target port number for the IMAP server").build());
        cliOptions.addOption(Option.builder("u").longOpt("user").required().hasArg().argName("targetUser")
                .desc("Set the user to connect to the target IMAP server").build());
        cliOptions.addOption(Option.builder("f").longOpt("folder").hasArg().argName("folderName")
                .desc("Set the sub-folder in which messages should be copied").build());
        cliOptions.addOptionGroup(new OptionGroup()
                .addOption(Option.builder().longOpt("ssl")
                        .desc("Use SSL when connecting to the target server (SSL is always used for GMail)").build())
                .addOption(Option.builder().longOpt("starttls").desc("Use STARTTLS to connect to the target host")
                        .build()));
        cliOptions.addOption(Option.builder().longOpt("google-connection-pool-size").hasArg().argName("integer")
                .desc("Set the maximum number of simultanious connections to GMail").build());
        cliOptions.addOption(Option.builder().longOpt("target-connection-pool-size").hasArg().argName("integer")
                .desc("Set the maximum number of simultanious connections to the target server").build());

        CommandLine commandLine = null;
        try {
            commandLine = new DefaultParser().parse(cliOptions, args);
        } catch (ParseException e1) {
            System.err.println("Error: " + e1.getMessage());
            System.err.println();
            printHelp(System.err);
            System.exit(1);
        }
        if (commandLine.hasOption("help")) {
            printHelp(System.out);
            System.exit(0);
        }
        googleUser = commandLine.getOptionValue("google-user");
        targetHost = commandLine.getOptionValue("host");
        try {
            targetPort = Integer.parseInt(commandLine.getOptionValue("port", "-1"));
        } catch (NumberFormatException e) {
            System.err.println("Error: Option --port expects an integer argument");
            System.err.println();
            printHelp(System.err);
            System.exit(1);
            throw new AssertionError();
        }
        targetUser = commandLine.getOptionValue("user");
        targetFolerName = commandLine.getOptionValue("folder");
        useSSL = commandLine.hasOption("ssl");
        useStartTLS = commandLine.hasOption("starttls");
        try {
            googlePoolSize = Integer.parseInt(commandLine.getOptionValue("google-connection-pool-size", "-1"));
        } catch (final NumberFormatException e1) {
            System.err.println("Error: Option --google-connection-pool-size expects an integer argument");
            System.err.println();
            printHelp(System.err);
            System.exit(1);
            throw new AssertionError();
        }
        try {
            targetPoolSize = Integer.parseInt(commandLine.getOptionValue("target-connection-pool-size", "-1"));
        } catch (final NumberFormatException e1) {
            System.err.println("Error: Option --target-connection-pool-size expects an integer argument");
            System.err.println();
            printHelp(System.err);
            System.exit(1);
            throw new AssertionError();
        }
    }

    private void printHelp(PrintStream ps) {
        HelpFormatter helpFormatter = new HelpFormatter();
        PrintWriter pw = new PrintWriter(ps);
        helpFormatter.printHelp(pw, 120, "javar -jar gmail-transfer.jar",
                "Transfer all e-mails from a GMail account to another server using IMAP without duplicating"
                        + " messages that have several labels in GMail, and without loosing information (those labels) "
                        + "(however, the directory structure is changed, but this can then be easily reordered by hand)\n\n",
                cliOptions, 2, 2, "", true);
        pw.flush();
    }

}