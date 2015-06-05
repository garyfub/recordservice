package com.cloudera.impala.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.RealmChoiceCallback;
import javax.security.sasl.SaslException;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSaslClientTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

import com.cloudera.impala.thrift.TestService;

/**
 * Tests that the connection to the TestService works with different authentication
 * mechanisms.
 */
public class TestConnection {
  private static String host = "localhost";
  private static int port = 12345;
  private static boolean debug = false;

  // Callback for DIGEST-MD5 to provide additional client information. We need to
  // implement all 4 of the callbacks used for DIGEST-MD5 although we are only
  // interested in the user and password.
  // The user should be the user and the password is the delegation token.
  static final class DigestHandler implements CallbackHandler {
    private final String user;
    private final String password;

    public DigestHandler(String user, String password) {
      this.user = user;
      this.password = password;
    }

    @Override
    public void handle(Callback[] callbacks)
        throws IOException, UnsupportedCallbackException {
      for (Callback cb : callbacks) {
        if (cb instanceof RealmChoiceCallback) {
          continue; // Ignore.
        } else if (cb instanceof NameCallback) {
          ((NameCallback)cb).setName(user);
        } else if (cb instanceof PasswordCallback) {
          ((PasswordCallback)cb).setPassword(password.toCharArray());
        } else if (cb instanceof RealmCallback) {
          RealmCallback rcb = (RealmCallback)cb;
          rcb.setText(rcb.getDefaultText());
        } else {
          throw new UnsupportedCallbackException(cb, "Unexpected DIGEST-MD5 callback");
        }
      }
    }
  }

  /**
   * Try connecting to the server using kerberos or digest-md5.
   * For DIGEST-MD5, the password must be specified.
   * For delegation tokens, the password is the token generated by the server (which
   * the server stores to authenticate).
   */
  public static void testConnection(boolean kerberos, String password,
      boolean expectConnection) throws SaslException, TException {

    // tell SASL to use GSSAPI, which supports Kerberos, or DIGEST-MD5
    // which is what delegation tokens use.
    String mechanism = kerberos ? "GSSAPI" : "DIGEST-MD5";
    if (kerberos && password != null) {
      throw new RuntimeException("Password must be NULL when using kerberos");
    }

    // kerberos principal server - myprincipal/my.server.com@MY.REALM
    String server = kerberos ? "localhost" : "default";

    CallbackHandler callback = kerberos ? null : new DigestHandler("user", password);
    System.out.println("Connecting to " + host + ":" + port + " using " + mechanism);
    if (!kerberos) System.out.println("  Password: " + password);

    TTransport transport = new TSocket(host, port);
    TTransport saslTransport = new TSaslClientTransport(
        mechanism,
        null,
        "impala",
        server,
        //  Properties
        new HashMap<String, String>(),
        callback,
        transport);         //  underlying transport

    TestService.Client client =
        new TestService.Client(new TBinaryProtocol(saslTransport));
    try {
      saslTransport.open();
    } catch (TException e) {
      System.out.println("  Could not connect");
      if (expectConnection) throw e;
      return;
    }
    System.out.println("  Connected successfully");

    try {
      String echoResponse = client.Echo("Hello");
      System.out.println("  Echo Response: " + echoResponse);
    } catch (TException e) {
      System.err.println("  Could not call Echo()" + e);
      throw e;
    }

    try {
      String kerberosEchoResponse = client.EchoOnlyKerberos("Hello2");
      if (!kerberos) {
        throw new RuntimeException(
            "Should not have been able to call EchoOnlyKerberos()");
      }
      System.out.println("  Kerberos Echo Response: " + kerberosEchoResponse);
    } catch (TException e) {
      System.err.println("  Could not call EchoOnlyKerberos(): " + e.getMessage());
      if (kerberos) throw e;
    }

    transport.close();
  }

  public static void main(String[] args) throws SaslException, TException {
    if (args.length > 0 && args[0] == "-help") {
      System.out.println("Usage: [host] [port]");
      return;
    }
    if (args.length > 0) host = args[0];
    if (args.length > 1) port = Integer.valueOf(args[1]).intValue();
    System.setProperty("javax.security.auth.useSubjectCredsOnly", "false");

    if (debug) {
      // This enables the most detailed logging to the console.
      System.setProperty("javax.security.sasl.level", "FINEST");
      Logger logger = Logger.getLogger("javax.security.sasl");
      System.out.println("Logger: " + logger);
      logger.setLevel(Level.FINEST);
      Handler handler = new ConsoleHandler();
      handler.setLevel(Level.FINEST);
      logger.addHandler(handler);
    }

    // Test with kerberos.
    testConnection(true, null, true);

    // Server is hard-coded to accept 'admin' for password, user is ignored..
    testConnection(false, "admin", true);

    // Test with bad password. This would be like passing the wrong delegation token.
    testConnection(false, "badpassword", false);
  }
}
