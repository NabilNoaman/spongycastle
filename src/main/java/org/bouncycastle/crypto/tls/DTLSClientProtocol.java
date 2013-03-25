package org.bouncycastle.crypto.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import org.bouncycastle.util.Arrays;

public class DTLSClientProtocol extends DTLSProtocol {

    public DTLSClientProtocol(SecureRandom secureRandom) {
        super(secureRandom);
    }

    public DTLSTransport connect(TlsClient client, DatagramTransport transport) throws IOException {

        if (client == null)
            throw new IllegalArgumentException("'client' cannot be null");
        if (transport == null)
            throw new IllegalArgumentException("'transport' cannot be null");

        ClientHandshakeState state = new ClientHandshakeState();
        state.client = client;
        state.clientContext = new TlsClientContextImpl(secureRandom, new SecurityParameters());
        state.clientContext.getSecurityParameters().clientRandom = TlsProtocol
            .createRandomBlock(secureRandom);
        client.init(state.clientContext);

        DTLSRecordLayer recordLayer = new DTLSRecordLayer(transport, state.clientContext,
            ContentType.handshake);
        DTLSReliableHandshake handshake = new DTLSReliableHandshake(recordLayer);

        byte[] clientHelloBody = generateClientHello(state, client);
        handshake.sendMessage(HandshakeType.client_hello, clientHelloBody);

        DTLSReliableHandshake.Message serverMessage = handshake.receiveMessage();

        {
            // NOTE: After receiving a record from the server, we discover the record layer version
            ProtocolVersion server_version = recordLayer.getDiscoveredPeerVersion();
            ProtocolVersion client_version = state.clientContext.getClientVersion();

            if (!server_version.isEqualOrEarlierVersionOf(client_version)) {
                // TODO Alert
                // this.failWithError(AlertLevel.fatal, AlertDescription.illegal_parameter);
            }

            state.clientContext.setServerVersion(server_version);
            client.notifyServerVersion(server_version);
        }

        while (serverMessage.getType() == HandshakeType.hello_verify_request) {
            byte[] cookie = parseHelloVerifyRequest(state.clientContext, serverMessage.getBody());
            byte[] patched = patchClientHelloWithCookie(clientHelloBody, cookie);

            handshake.resetHandshakeMessagesDigest();
            handshake.sendMessage(HandshakeType.client_hello, patched);

            serverMessage = handshake.receiveMessage();
        }

        if (serverMessage.getType() == HandshakeType.server_hello) {
            processServerHello(state, serverMessage.getBody());
            serverMessage = handshake.receiveMessage();
        } else {
            // TODO Alert
        }

        if (serverMessage.getType() == HandshakeType.supplemental_data) {
            processServerSupplementalData(state, serverMessage.getBody());
            serverMessage = handshake.receiveMessage();
        } else {
            state.client.processServerSupplementalData(null);
        }

        if (serverMessage.getType() == HandshakeType.certificate) {
            processServerCertificate(state, serverMessage.getBody());
            serverMessage = handshake.receiveMessage();
        } else {
            // Okay, Certificate is optional
            state.keyExchange.skipServerCertificate();
        }

        if (serverMessage.getType() == HandshakeType.server_key_exchange) {
            processServerKeyExchange(state, serverMessage.getBody());
            serverMessage = handshake.receiveMessage();
        } else {
            // Okay, ServerKeyExchange is optional
            state.keyExchange.skipServerKeyExchange();
        }

        if (serverMessage.getType() == HandshakeType.certificate_request) {
            processCertificateRequest(state, serverMessage.getBody());
            serverMessage = handshake.receiveMessage();
        } else {
            // Okay, CertificateRequest is optional
        }

        if (serverMessage.getType() == HandshakeType.server_hello_done) {
            if (serverMessage.getBody().length != 0) {
                // TODO Alert
            }
        } else {
            // TODO Alert
        }

        Vector clientSupplementalData = state.client.getClientSupplementalData();
        if (clientSupplementalData != null) {
            byte[] supplementalDataBody = generateSupplementalData(clientSupplementalData);
            handshake.sendMessage(HandshakeType.supplemental_data, supplementalDataBody);
        }

        if (state.certificateRequest != null) {
            state.clientCredentials = state.authentication
                .getClientCredentials(state.certificateRequest);

            Certificate clientCertificate = Certificate.EMPTY_CHAIN;
            if (state.clientCredentials != null) {
                clientCertificate = state.clientCredentials.getCertificate();
            }

            byte[] certificateBody = generateCertificate(clientCertificate);
            handshake.sendMessage(HandshakeType.certificate, certificateBody);
        }

        if (state.clientCredentials != null) {
            state.keyExchange.processClientCredentials(state.clientCredentials);
        } else {
            state.keyExchange.skipClientCredentials();
        }

        byte[] clientKeyExchangeBody = generateClientKeyExchange(state);
        handshake.sendMessage(HandshakeType.client_key_exchange, clientKeyExchangeBody);

        // Calculate the master_secret
        {
            byte[] pms = state.keyExchange.generatePremasterSecret();

            try {
                state.clientContext.getSecurityParameters().masterSecret = TlsUtils
                    .calculateMasterSecret(state.clientContext, pms);
            } finally {
                // TODO Is there a way to ensure the data is really overwritten?
                if (pms != null) {
                    Arrays.fill(pms, (byte) 0);
                }
            }
        }

        if (state.clientCredentials instanceof TlsSignerCredentials) {
            TlsSignerCredentials signerCredentials = (TlsSignerCredentials) state.clientCredentials;

            byte[] md5andsha1 = handshake.getCurrentHash();
            byte[] signature = signerCredentials.generateCertificateSignature(md5andsha1);

            byte[] certificateVerifyBody = generateCertificateVerify(state, signature);
            handshake.sendMessage(HandshakeType.certificate_verify, certificateVerifyBody);
        }

        recordLayer.initPendingEpoch(state.client.getCipher());

        byte[] clientVerifyData = TlsUtils.calculateVerifyData(state.clientContext,
            "client finished", handshake.getCurrentHash());
        handshake.sendMessage(HandshakeType.finished, clientVerifyData);

        // NOTE: Calculated exclusive of the actual Finished message from the server
        byte[] expectedServerVerifyData = TlsUtils.calculateVerifyData(state.clientContext,
            "server finished", handshake.getCurrentHash());
        serverMessage = handshake.receiveMessage();

        if (serverMessage.getType() == HandshakeType.finished) {
            processFinished(serverMessage.getBody(), expectedServerVerifyData);
        } else {
            // TODO Alert
        }

        handshake.finish();

        recordLayer.handshakeSuccessful();

        return new DTLSTransport(recordLayer);
    }

    protected byte[] generateCertificateVerify(ClientHandshakeState state, byte[] signature)
        throws IOException {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        TlsUtils.writeOpaque16(signature, buf);
        return buf.toByteArray();
    }

    protected byte[] generateClientHello(ClientHandshakeState state, TlsClient client)
        throws IOException {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        ProtocolVersion client_version = client.getClientVersion();
        if (!client_version.isDTLS()) {
            // TODO Alert
        }

        state.clientContext.setClientVersion(client_version);
        TlsUtils.writeVersion(client_version, buf);

        buf.write(state.clientContext.getSecurityParameters().getClientRandom());

        // Length of Session id
        TlsUtils.writeUint8((short) 0, buf);

        // Length of cookie
        TlsUtils.writeUint8((short) 0, buf);

        /*
         * Cipher suites
         */
        state.offeredCipherSuites = client.getCipherSuites();

        for (int cipherSuite : state.offeredCipherSuites) {
            switch (cipherSuite) {
            case CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5:
            case CipherSuite.TLS_RSA_WITH_RC4_128_MD5:
            case CipherSuite.TLS_RSA_WITH_RC4_128_SHA:
            case CipherSuite.TLS_DH_anon_EXPORT_WITH_RC4_40_MD5:
            case CipherSuite.TLS_DH_anon_WITH_RC4_128_MD5:
            case CipherSuite.TLS_PSK_WITH_RC4_128_SHA:
            case CipherSuite.TLS_DHE_PSK_WITH_RC4_128_SHA:
            case CipherSuite.TLS_RSA_PSK_WITH_RC4_128_SHA:
            case CipherSuite.TLS_ECDH_ECDSA_WITH_RC4_128_SHA:
            case CipherSuite.TLS_ECDHE_ECDSA_WITH_RC4_128_SHA:
            case CipherSuite.TLS_ECDH_RSA_WITH_RC4_128_SHA:
            case CipherSuite.TLS_ECDHE_RSA_WITH_RC4_128_SHA:
            case CipherSuite.TLS_ECDH_anon_WITH_RC4_128_SHA:
                // TODO Alert
                throw new IllegalStateException(
                    "Client offered an RC4 cipher suite: RC4 MUST NOT be used with DTLS");
            }
        }

        // Integer -> byte[]
        state.clientExtensions = client.getClientExtensions();

        // Cipher Suites (and SCSV)
        {
            /*
             * RFC 5746 3.4. The client MUST include either an empty "renegotiation_info" extension,
             * or the TLS_EMPTY_RENEGOTIATION_INFO_SCSV signaling cipher suite value in the
             * ClientHello. Including both is NOT RECOMMENDED.
             */
            boolean noRenegExt = state.clientExtensions == null
                || state.clientExtensions.get(EXT_RenegotiationInfo) == null;

            int count = state.offeredCipherSuites.length;
            if (noRenegExt) {
                // Note: 1 extra slot for TLS_EMPTY_RENEGOTIATION_INFO_SCSV
                ++count;
            }

            TlsUtils.writeUint16(2 * count, buf);
            TlsUtils.writeUint16Array(state.offeredCipherSuites, buf);

            if (noRenegExt) {
                TlsUtils.writeUint16(CipherSuite.TLS_EMPTY_RENEGOTIATION_INFO_SCSV, buf);
            }
        }

        // TODO Add support for compression
        // Compression methods
        // state.offeredCompressionMethods = client.getCompressionMethods();
        state.offeredCompressionMethods = new short[] { CompressionMethod.NULL };

        TlsUtils.writeUint8((short) state.offeredCompressionMethods.length, buf);
        TlsUtils.writeUint8Array(state.offeredCompressionMethods, buf);

        // Extensions
        if (state.clientExtensions != null) {
            TlsProtocol.writeExtensions(buf, state.clientExtensions);
        }

        return buf.toByteArray();
    }

    protected byte[] generateClientKeyExchange(ClientHandshakeState state) throws IOException {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        state.keyExchange.generateClientKeyExchange(buf);
        return buf.toByteArray();
    }

    protected void processCertificateRequest(ClientHandshakeState state, byte[] body)
        throws IOException {

        if (state.authentication == null) {
            // TODO Alert
        }

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        state.certificateRequest = CertificateRequest.parse(buf);

        TlsProtocol.assertEmpty(buf);

        state.keyExchange.validateCertificateRequest(state.certificateRequest);
    }

    protected void processServerCertificate(ClientHandshakeState state, byte[] body)
        throws IOException {

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        Certificate serverCertificate = Certificate.parse(buf);

        TlsProtocol.assertEmpty(buf);

        state.keyExchange.processServerCertificate(serverCertificate);
        state.authentication = state.client.getAuthentication();
        state.authentication.notifyServerCertificate(serverCertificate);
    }

    protected void processServerHello(ClientHandshakeState state, byte[] body) throws IOException {

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        ProtocolVersion server_version = TlsUtils.readVersion(buf);
        if (!server_version.equals(state.clientContext.getServerVersion())) {
            // TODO Alert
        }

        byte[] server_random = new byte[32];
        TlsUtils.readFully(server_random, buf);
        state.clientContext.getSecurityParameters().serverRandom = server_random;

        byte[] sessionID = TlsUtils.readOpaque8(buf);
        if (sessionID.length > 32) {
            // TODO Alert
        }
        state.client.notifySessionID(sessionID);

        int selectedCipherSuite = TlsUtils.readUint16(buf);
        if (!TlsProtocol.arrayContains(state.offeredCipherSuites, selectedCipherSuite)
            || selectedCipherSuite == CipherSuite.TLS_EMPTY_RENEGOTIATION_INFO_SCSV) {
            // TODO Alert
        }
        state.client.notifySelectedCipherSuite(selectedCipherSuite);

        short selectedCompressionMethod = TlsUtils.readUint8(buf);
        if (!TlsProtocol.arrayContains(state.offeredCompressionMethods, selectedCompressionMethod)) {
            // TODO Alert
        }
        state.client.notifySelectedCompressionMethod(selectedCompressionMethod);

        /*
         * RFC3546 2.2 The extended server hello message format MAY be sent in place of the server
         * hello message when the client has requested extended functionality via the extended
         * client hello message specified in Section 2.1. ... Note that the extended server hello
         * message is only sent in response to an extended client hello message. This prevents the
         * possibility that the extended server hello message could "break" existing TLS 1.0
         * clients.
         */

        /*
         * TODO RFC 3546 2.3 If [...] the older session is resumed, then the server MUST ignore
         * extensions appearing in the client hello, and send a server hello containing no
         * extensions.
         */

        // Integer -> byte[]
        Hashtable serverExtensions = TlsProtocol.readExtensions(buf);

        /*
         * RFC 3546 2.2 Note that the extended server hello message is only sent in response to an
         * extended client hello message. However, see RFC 5746 exception below. We always include
         * the SCSV, so an Extended Server Hello is always allowed.
         */
        if (serverExtensions != null) {
            Enumeration e = serverExtensions.keys();
            while (e.hasMoreElements()) {
                Integer extType = (Integer) e.nextElement();

                /*
                 * RFC 5746 Note that sending a "renegotiation_info" extension in response to a
                 * ClientHello containing only the SCSV is an explicit exception to the prohibition
                 * in RFC 5246, Section 7.4.1.4, on the server sending unsolicited extensions and is
                 * only allowed because the client is signaling its willingness to receive the
                 * extension via the TLS_EMPTY_RENEGOTIATION_INFO_SCSV SCSV. TLS implementations
                 * MUST continue to comply with Section 7.4.1.4 for all other extensions.
                 */
                if (!extType.equals(EXT_RenegotiationInfo)
                    && (state.clientExtensions == null || state.clientExtensions.get(extType) == null)) {
                    /*
                     * RFC 3546 2.3 Note that for all extension types (including those defined in
                     * future), the extension type MUST NOT appear in the extended server hello
                     * unless the same extension type appeared in the corresponding client hello.
                     * Thus clients MUST abort the handshake if they receive an extension type in
                     * the extended server hello that they did not request in the associated
                     * (extended) client hello.
                     */
                    // TODO Alert
                    // this.failWithError(AlertLevel.fatal, AlertDescription.unsupported_extension);
                }
            }

            /*
             * RFC 5746 3.4. Client Behavior: Initial Handshake
             */
            {
                /*
                 * When a ServerHello is received, the client MUST check if it includes the
                 * "renegotiation_info" extension:
                 */
                byte[] renegExtValue = (byte[]) serverExtensions.get(EXT_RenegotiationInfo);
                if (renegExtValue != null) {
                    /*
                     * If the extension is present, set the secure_renegotiation flag to TRUE. The
                     * client MUST then verify that the length of the "renegotiated_connection"
                     * field is zero, and if it is not, MUST abort the handshake (by sending a fatal
                     * handshake_failure alert).
                     */
                    state.secure_renegotiation = true;

                    if (!Arrays.constantTimeAreEqual(renegExtValue,
                        TlsProtocol.createRenegotiationInfo(EMPTY_BYTES))) {
                        // TODO Alert
                        // this.failWithError(AlertLevel.fatal, AlertDescription.handshake_failure);
                    }
                }
            }
        }

        state.client.notifySecureRenegotiation(state.secure_renegotiation);

        if (state.clientExtensions != null) {
            state.client.processServerExtensions(serverExtensions);
        }

        state.keyExchange = state.client.getKeyExchange();
        state.keyExchange.init(state.clientContext);
    }

    protected void processServerKeyExchange(ClientHandshakeState state, byte[] body)
        throws IOException {

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        state.keyExchange.processServerKeyExchange(buf);

        TlsProtocol.assertEmpty(buf);
    }

    protected void processServerSupplementalData(ClientHandshakeState state, byte[] body)
        throws IOException {
        ByteArrayInputStream buf = new ByteArrayInputStream(body);
        Vector serverSupplementalData = TlsProtocol.readSupplementalDataMessage(buf);
        state.client.processServerSupplementalData(serverSupplementalData);
    }

    protected static byte[] parseHelloVerifyRequest(TlsContext context, byte[] body)
        throws IOException {

        ByteArrayInputStream buf = new ByteArrayInputStream(body);

        ProtocolVersion server_version = TlsUtils.readVersion(buf);
        if (!server_version.equals(context.getServerVersion())) {
            // TODO Alert
        }

        byte[] cookie = TlsUtils.readOpaque8(buf);

        TlsProtocol.assertEmpty(buf);

        if (cookie.length < 1 || cookie.length > 32) {
            // TODO Alert
        }

        return cookie;
    }

    protected static byte[] patchClientHelloWithCookie(byte[] clientHelloBody, byte[] cookie)
        throws IOException {

        int sessionIDPos = 34;
        int sessionIDLength = TlsUtils.readUint8(clientHelloBody, sessionIDPos);

        int cookieLengthPos = sessionIDPos + 1 + sessionIDLength;
        int cookiePos = cookieLengthPos + 1;

        byte[] patched = new byte[clientHelloBody.length + cookie.length];
        System.arraycopy(clientHelloBody, 0, patched, 0, cookieLengthPos);
        TlsUtils.writeUint8((short) cookie.length, patched, cookieLengthPos);
        System.arraycopy(cookie, 0, patched, cookiePos, cookie.length);
        System.arraycopy(clientHelloBody, cookiePos, patched, cookiePos + cookie.length,
            clientHelloBody.length - cookiePos);

        return patched;
    }

    protected static class ClientHandshakeState {
        TlsClient client = null;
        TlsClientContextImpl clientContext = null;
        int[] offeredCipherSuites = null;
        short[] offeredCompressionMethods = null;
        Hashtable clientExtensions = null;
        boolean secure_renegotiation = false;
        TlsKeyExchange keyExchange = null;
        TlsAuthentication authentication = null;
        CertificateRequest certificateRequest = null;
        TlsCredentials clientCredentials = null;
    }
}