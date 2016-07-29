package net.trebuh.gimapTransfer;

import java.util.Formatter;
import java.util.Scanner;

import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;

public final class ConsolePasswordAuthenticator extends Authenticator {
    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        @SuppressWarnings("resource")
        final Formatter fmt = new Formatter(System.out);
        fmt.format("Connecting to %s mail service on host %s, port %s.%n", getRequestingProtocol(),
                getRequestingSite(), getRequestingPort());
        if (getRequestingPrompt() != null)
            fmt.format("%s%n", getRequestingPrompt());
        fmt.format("%nUser name: %s%nPassword: ", getDefaultUserName());
        fmt.flush();
        @SuppressWarnings("resource")
        final String password = new Scanner(System.in).next();
        return new PasswordAuthentication(getDefaultUserName(), password);
    }
}