package com.fsck.k9.mail.store.pop3;


import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.fsck.k9.mail.AuthType;
import com.fsck.k9.mail.Authentication;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.CertificateValidationException;
import com.fsck.k9.mail.ConnectionSecurity;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.filter.Base64;
import com.fsck.k9.mail.filter.Hex;
import com.fsck.k9.mail.ssl.TrustedSocketFactory;
import com.fsck.k9.mail.store.RemoteStore;
import javax.net.ssl.SSLException;
import timber.log.Timber;

import static com.fsck.k9.mail.CertificateValidationException.Reason.MissingCapability;
import static com.fsck.k9.mail.K9MailLib.DEBUG_PROTOCOL_POP3;
import static com.fsck.k9.mail.store.pop3.Pop3Commands.*;


public class Pop3Connection {

    private final String username;
    private final String password;
    private Socket socket;
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private Pop3Capabilities capabilities;

    /**
     * This value is {@code true} if the server supports the CAPA command but doesn't advertise
     * support for the TOP command OR if the server doesn't support the CAPA command and we
     * already unsuccessfully tried to use the TOP command.
     */
    private boolean topNotAdvertised;

    Pop3Connection(String host, int port, ConnectionSecurity connectionSecurity, AuthType authType, 
            String clientCertificateAlias, String username, String password,
            TrustedSocketFactory trustedSocketFactory) throws MessagingException {
        this.username = username;
        this.password = password;

        try {
            SocketAddress socketAddress = new InetSocketAddress(host, port);
            if (connectionSecurity == ConnectionSecurity.SSL_TLS_REQUIRED) {
                socket = trustedSocketFactory.createSocket(null, host, port, clientCertificateAlias);
            } else {
                socket = new Socket();
            }
    
            socket.connect(socketAddress, RemoteStore.SOCKET_CONNECT_TIMEOUT);
            in = new BufferedInputStream(socket.getInputStream(), 1024);
            out = new BufferedOutputStream(socket.getOutputStream(), 512);
    
            socket.setSoTimeout(RemoteStore.SOCKET_READ_TIMEOUT);
    
            if (!isOpen()) {
                throw new MessagingException("Unable to connect socket");
            }
    
            String serverGreeting = executeSimpleCommand(null);
    
            capabilities = getCapabilities();
            if (connectionSecurity == ConnectionSecurity.STARTTLS_REQUIRED) {
    
                if (capabilities.stls) {
                    executeSimpleCommand(STLS_COMMAND);
    
                    socket = trustedSocketFactory.createSocket(
                            socket,
                            host,
                            port,
                            clientCertificateAlias);
                    socket.setSoTimeout(RemoteStore.SOCKET_READ_TIMEOUT);
                    in = new BufferedInputStream(socket.getInputStream(), 1024);
                    out = new BufferedOutputStream(socket.getOutputStream(), 512);
                    if (!isOpen()) {
                        throw new MessagingException("Unable to connect socket");
                    }
                    capabilities = getCapabilities();
                } else {
                    /*
                     * This exception triggers a "Certificate error"
                     * notification that takes the user to the incoming
                     * server settings for review. This might be needed if
                     * the account was configured with an obsolete
                     * "STARTTLS (if available)" setting.
                     */
                    throw new CertificateValidationException(
                            "STARTTLS connection security not available");
                }
            }
    
            switch (authType) {
                case PLAIN:
                    if (capabilities.authPlain) {
                        authPlain();
                    } else {
                        login();
                    }
                    break;
    
                case CRAM_MD5:
                    if (capabilities.cramMD5) {
                        authCramMD5();
                    } else {
                        authAPOP(serverGreeting);
                    }
                    break;
    
                case EXTERNAL:
                    if (capabilities.external) {
                        authExternal();
                    } else {
                        // Provide notification to user of a problem authenticating using client certificates
                        throw new CertificateValidationException(MissingCapability);
                    }
                    break;
    
                default:
                    throw new MessagingException(
                            "Unhandled authentication method found in the server settings (bug).");
            }
        } catch (SSLException e) {
            if (e.getCause() instanceof CertificateException) {
                throw new CertificateValidationException(e.getMessage(), e);
            } else {
                throw new MessagingException("Unable to connect", e);
            }
        } catch (GeneralSecurityException gse) {
            throw new MessagingException(
                    "Unable to open connection to POP server due to security error.", gse);
        } catch (IOException ioe) {
            throw new MessagingException("Unable to open connection to POP server.", ioe);
        }
    }

    private String executeSimpleCommand(Object o) {
        return null;
    }

    boolean isOpen() {
        return (in != null && out != null && socket != null
                && socket.isConnected() && !socket.isClosed());
    }

    private Pop3Capabilities getCapabilities() throws IOException {
        Pop3Capabilities capabilities = new Pop3Capabilities();
        try {
            /*
             * Try sending an AUTH command with no arguments.
             *
             * The server may respond with a list of supported SASL
             * authentication mechanisms.
             *
             * Ref.: http://tools.ietf.org/html/draft-myers-sasl-pop3-05
             *
             * While this never became a standard, there are servers that
             * support it, and Thunderbird includes this check.
             */
            String response = executeSimpleCommand(AUTH_COMMAND);
            while ((response = readLine()) != null) {
                if (response.equals(".")) {
                    break;
                }
                response = response.toUpperCase(Locale.US);
                if (response.equals(AUTH_PLAIN_CAPABILITY)) {
                    capabilities.authPlain = true;
                } else if (response.equals(AUTH_CRAM_MD5_CAPABILITY)) {
                    capabilities.cramMD5 = true;
                } else if (response.equals(AUTH_EXTERNAL_CAPABILITY)) {
                    capabilities.external = true;
                }
            }
        } catch (MessagingException e) {
            // Assume AUTH command with no arguments is not supported.
        }
        try {
            String response = executeSimpleCommand(CAPA_COMMAND);
            while ((response = readLine()) != null) {
                if (response.equals(".")) {
                    break;
                }
                response = response.toUpperCase(Locale.US);
                if (response.equals(STLS_CAPABILITY)) {
                    capabilities.stls = true;
                } else if (response.equals(UIDL_CAPABILITY)) {
                    capabilities.uidl = true;
                } else if (response.equals(TOP_CAPABILITY)) {
                    capabilities.top = true;
                } else if (response.startsWith(SASL_CAPABILITY)) {
                    List<String> saslAuthMechanisms = Arrays.asList(response.split(" "));
                    if (saslAuthMechanisms.contains(AUTH_PLAIN_CAPABILITY)) {
                        capabilities.authPlain = true;
                    }
                    if (saslAuthMechanisms.contains(AUTH_CRAM_MD5_CAPABILITY)) {
                        capabilities.cramMD5 = true;
                    }
                }
            }

            if (!capabilities.top) {
                /*
                 * If the CAPA command is supported but it doesn't advertise support for the
                 * TOP command, we won't check for it manually.
                 */
                topNotAdvertised = true;
            }
        } catch (MessagingException me) {
            /*
             * The server may not support the CAPA command, so we just eat this Exception
             * and allow the empty capabilities object to be returned.
             */
        }
        return capabilities;
    }

    private void login() throws MessagingException {
        executeSimpleCommand(USER_COMMAND + " " + username);
        try {
            executeSimpleCommand(PASS_COMMAND + " " + password, true);
        } catch (Pop3ErrorResponse e) {
            throw new AuthenticationFailedException(
                    "POP3 login authentication failed: " + e.getMessage(), e);
        }
    }

    private void authPlain() throws MessagingException {
        executeSimpleCommand("AUTH PLAIN");
        try {
            byte[] encodedBytes = Base64.encodeBase64(("\000" + username
                    + "\000" + password).getBytes());
            executeSimpleCommand(new String(encodedBytes), true);
        } catch (Pop3ErrorResponse e) {
            throw new AuthenticationFailedException(
                    "POP3 SASL auth PLAIN authentication failed: "
                            + e.getMessage(), e);
        }
    }

    private void authAPOP(String serverGreeting) throws MessagingException {
        // regex based on RFC 2449 (3.) "Greeting"
        String timestamp = serverGreeting.replaceFirst(
                "^\\+OK *(?:\\[[^\\]]+\\])?[^<]*(<[^>]*>)?[^<]*$", "$1");
        if ("".equals(timestamp)) {
            throw new MessagingException(
                    "APOP authentication is not supported");
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new MessagingException(
                    "MD5 failure during POP3 auth APOP", e);
        }
        byte[] digest = md.digest((timestamp + password).getBytes());
        String hexDigest = Hex.encodeHex(digest);
        try {
            executeSimpleCommand("APOP " + username + " " + hexDigest, true);
        } catch (Pop3ErrorResponse e) {
            throw new AuthenticationFailedException(
                    "POP3 APOP authentication failed: " + e.getMessage(), e);
        }
    }

    private void authCramMD5() throws MessagingException {
        String b64Nonce = executeSimpleCommand("AUTH CRAM-MD5").replace("+ ", "");

        String b64CRAM = Authentication.computeCramMd5(username, password, b64Nonce);
        try {
            executeSimpleCommand(b64CRAM, true);
        } catch (Pop3ErrorResponse e) {
            throw new AuthenticationFailedException(
                    "POP3 CRAM-MD5 authentication failed: "
                            + e.getMessage(), e);
        }
    }

    private void authExternal() throws MessagingException {
        try {
            executeSimpleCommand(
                    String.format("AUTH EXTERNAL %s",
                            Base64.encode(username)), false);
        } catch (Pop3ErrorResponse e) {
            /*
             * Provide notification to the user of a problem authenticating
             * using client certificates. We don't use an
             * AuthenticationFailedException because that would trigger a
             * "Username or password incorrect" notification in
             * AccountSetupCheckSettings.
             */
            throw new CertificateValidationException(
                    "POP3 client certificate authentication failed: " + e.getMessage(), e);
        }
    }

    private void writeLine(String s) throws IOException {
        out.write(s.getBytes());
        out.write('\r');
        out.write('\n');
        out.flush();
    }

    String executeSimpleCommand(String command) throws MessagingException {
        return executeSimpleCommand(command, false);
    }

    String executeSimpleCommand(String command, boolean sensitive) throws MessagingException {
        try {

            if (command != null) {
                if (K9MailLib.isDebug() && DEBUG_PROTOCOL_POP3) {
                    if (sensitive && !K9MailLib.isDebugSensitive()) {
                        Timber.d(">>> [Command Hidden, Enable Sensitive Debug Logging To Show]");
                    } else {
                        Timber.d(">>> %s", command);
                    }
                }

                writeLine(command);
            }

            String response = readLine();
            if (response.length() == 0 || response.charAt(0) != '+') {
                throw new Pop3ErrorResponse(response);
            }

            return response;
        } catch (MessagingException me) {
            throw me;
        } catch (Exception e) {
            close();
            throw new MessagingException("Unable to execute POP3 command", e);
        }
    }

    String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int d = in.read();
        if (d == -1) {
            throw new IOException("End of stream reached while trying to read line.");
        }
        do {
            if (((char)d) == '\r') {
                continue;
            } else if (((char)d) == '\n') {
                break;
            } else {
                sb.append((char)d);
            }
        } while ((d = in.read()) != -1);
        String ret = sb.toString();
        if (K9MailLib.isDebug() && DEBUG_PROTOCOL_POP3) {
            Timber.d("<<< %s", ret);
        }
        return ret;
    }

    void close() {
        try {
            in.close();
        } catch (Exception e) {
            /*
             * May fail if the connection is already closed.
             */
        }
        try {
            out.close();
        } catch (Exception e) {
            /*
             * May fail if the connection is already closed.
             */
        }
        try {
            socket.close();
        } catch (Exception e) {
            /*
             * May fail if the connection is already closed.
             */
        }
        in = null;
        out = null;
        socket = null;
    }

    boolean supportsTop() {
        return capabilities.top;
    }

    boolean isTopNotAdvertised() {
        return topNotAdvertised;
    }

    public void setSupportsTop(boolean supportsTop) {
        this.capabilities.top = supportsTop;
    }

    public void setTopNotAdvertised(boolean topNotAdvertised) {
        this.topNotAdvertised = topNotAdvertised;
    }

    public boolean supportsUidl() {
        return this.capabilities.uidl;
    }
}
