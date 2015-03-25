/*
 * Kontalk Android client
 * Copyright (C) 2015 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.client;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.SASLAuthentication;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.sm.predicates.ForMatchingPredicateOrAfterXStanzas;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest;

import android.util.Log;

import org.kontalk.BuildConfig;
import org.kontalk.Kontalk;
import org.kontalk.authenticator.LegacyAuthentication;


public class KontalkConnection extends XMPPTCPConnection {
    private static final String TAG = Kontalk.TAG;

    /** Packet reply timeout. */
    public static final int DEFAULT_PACKET_TIMEOUT = 15000;

    protected EndpointServer mServer;

    public KontalkConnection(String resource, EndpointServer server, boolean secure,
        boolean acceptAnyCertificate, KeyStore trustStore, String legacyAuthToken)
            throws XMPPException {

        this(resource, server, secure, null, null, acceptAnyCertificate, trustStore, legacyAuthToken);
    }

    public KontalkConnection(String resource, EndpointServer server, boolean secure,
            PrivateKey privateKey, X509Certificate bridgeCert,
            boolean acceptAnyCertificate, KeyStore trustStore, String legacyAuthToken) throws XMPPException {

        super(buildConfiguration(resource, server, secure,
            privateKey, bridgeCert, acceptAnyCertificate, trustStore, legacyAuthToken));

        mServer = server;

        // enable SM without resumption
        setUseStreamManagement(true);
        setUseStreamManagementResumption(false);
        // set custom ack predicate
        addRequestAckPredicate(AckPredicate.INSTANCE);
        // set custom packet reply timeout
        setPacketReplyTimeout(DEFAULT_PACKET_TIMEOUT);
    }

    private static XMPPTCPConnectionConfiguration buildConfiguration(String resource,
        EndpointServer server, boolean secure, PrivateKey privateKey, X509Certificate bridgeCert,
        boolean acceptAnyCertificate, KeyStore trustStore, String legacyAuthToken) {
        XMPPTCPConnectionConfiguration.Builder builder =
            XMPPTCPConnectionConfiguration.builder();

        builder
            // connection parameters
            .setHost(server.getHost())
            .setPort(secure ? server.getSecurePort() : server.getPort())
            .setServiceName(server.getNetwork())
            .setResource(resource)
            // the dummy value is not actually used
            .setUsernameAndPassword(null, legacyAuthToken != null ? legacyAuthToken : "dummy")
            // for EXTERNAL
            .allowEmptyOrNullUsernames()
            // enable compression
            .setCompressionEnabled(true)
            // enable encryption
            .setSecurityMode(secure ? SecurityMode.disabled : SecurityMode.required)
            // we will send a custom presence
            .setSendPresence(false)
            // disable session initiation
            .setLegacySessionDisabled(true)
            // enable debugging
            .setDebuggerEnabled(BuildConfig.DEBUG);

        // setup SSL
        setupSSL(builder, secure, privateKey, bridgeCert, acceptAnyCertificate, trustStore);

        return builder.build();
    }

    private static void setupSSL(XMPPTCPConnectionConfiguration.Builder builder,
        boolean direct, PrivateKey privateKey, X509Certificate bridgeCert,
        boolean acceptAnyCertificate, KeyStore trustStore) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");

            KeyManager[] km = null;
            if (privateKey != null && bridgeCert != null) {
                // in-memory keystore
                KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
                keystore.load(null, null);
                keystore.setKeyEntry("private", privateKey, null, new Certificate[] { bridgeCert });

                // key managers
                KeyManagerFactory kmFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmFactory.init(keystore, null);

                km = kmFactory.getKeyManagers();

                // disable PLAIN mechanism if not upgrading from legacy
                if (!LegacyAuthentication.isUpgrading()) {
                    // blacklist PLAIN mechanism
                    SASLAuthentication.blacklistSASLMechanism("PLAIN");
                }
            }

            // trust managers
            TrustManager[] tm;

            if (acceptAnyCertificate) {
                tm = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {
                        }
                    }
                };
            }

            else {
                // builtin keystore
                TrustManagerFactory tmFactory = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmFactory.init(trustStore);

                tm = tmFactory.getTrustManagers();
            }

            ctx.init(km, tm, null);
            if (direct)
                builder.setSocketFactory(ctx.getSocketFactory());
            else
                builder.setCustomSSLContext(ctx);

            // SASL EXTERNAL is already enabled in Smack
        }
        catch (Exception e) {
            Log.w(TAG, "unable to setup SSL connection", e);
        }
    }

    /**
     * A custom ack predicate that allows ack after a message with a delivery
     * receipt, a receipt request or a body, or after 5 stanzas.
     */
    private static final class AckPredicate extends ForMatchingPredicateOrAfterXStanzas {

        public static final AckPredicate INSTANCE = new AckPredicate();

        private AckPredicate() {
            super(new PacketFilter() {
                @Override
                public boolean accept(Stanza packet) {
                    return (packet instanceof Message &&
                        (((Message) packet).getBody() != null ||
                          DeliveryReceipt.from(packet) != null ||
                           DeliveryReceiptRequest.from(packet) != null));
                }
            }, 5);
        }
    }
}
