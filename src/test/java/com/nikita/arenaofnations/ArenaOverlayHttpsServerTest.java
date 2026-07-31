package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HTTPS overlay server tests using an ephemeral local PKCS12 (keytool).
 * Does not start Minecraft and does not require Windows trust store setup.
 */
class ArenaOverlayHttpsServerTest {
	@TempDir
	Path tempDir;

	@AfterEach
	void tearDown() {
		ArenaOverlayHttpServer.stopForTest();
		ArenaOverlayStateService.get().resetSnapshotForTest();
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress("127.0.0.1", 0));
			return socket.getLocalPort();
		}
	}

	private Path createPkcs12(String password) throws Exception {
		Path keystore = tempDir.resolve("test-server.p12");
		Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
		if (!Files.isRegularFile(keytool)) {
			keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
		}
		ProcessBuilder builder = new ProcessBuilder(
				keytool.toString(),
				"-genkeypair",
				"-alias", "arena-overlay",
				"-keyalg", "RSA",
				"-keysize", "2048",
				"-validity", "365",
				"-dname", "CN=localhost",
				"-ext", "SAN=dns:localhost,dns:arena-overlay.test,ip:127.0.0.1",
				"-ext", "EKU=serverAuth",
				"-storetype", "PKCS12",
				"-keystore", keystore.toAbsolutePath().toString(),
				"-storepass", password,
				"-keypass", password);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), "keytool failed: " + output);
		assertTrue(Files.isRegularFile(keystore));
		return keystore;
	}

	private static SSLContext trustContextFromPkcs12(Path keystore, char[] password) throws Exception {
		KeyStore trustStore = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(keystore)) {
			trustStore.load(in, password);
		}
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(trustStore);
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, tmf.getTrustManagers(), null);
		return context;
	}

	private static HttpResponse<String> httpsGet(SSLContext ssl, String url) throws Exception {
		HttpClient client = HttpClient.newBuilder()
				.sslContext(ssl)
				.connectTimeout(Duration.ofSeconds(3))
				.build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(3))
				.GET()
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static HttpResponse<String> httpsPost(SSLContext ssl, String url) throws Exception {
		HttpClient client = HttpClient.newBuilder()
				.sslContext(ssl)
				.connectTimeout(Duration.ofSeconds(3))
				.build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(3))
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.header("Content-Type", "application/json")
				.build();
		return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@Test
	void certificateHasSanServerAuthAndPrivateKey() throws Exception {
		char[] password = "test-pass-1".toCharArray();
		Path keystore = createPkcs12(new String(password));
		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(keystore)) {
			ks.load(in, password);
		}
		String alias = null;
		Enumeration<String> aliases = ks.aliases();
		while (aliases.hasMoreElements()) {
			String candidate = aliases.nextElement();
			if (ks.isKeyEntry(candidate)) {
				alias = candidate;
				break;
			}
		}
		assertNotNull(alias);
		assertNotNull(ks.getKey(alias, password));
		Certificate cert = ks.getCertificate(alias);
		assertTrue(cert instanceof X509Certificate);
		X509Certificate x509 = (X509Certificate) cert;
		assertTrue(ArenaOverlayHttpsMaterial.certificateMatchesHostname(x509, "localhost"));
		assertTrue(ArenaOverlayHttpsMaterial.certificateMatchesHostname(x509, "arena-overlay.test"));
		assertTrue(ArenaOverlayHttpsMaterial.certificateMatchesHostname(x509, "127.0.0.1"));
		assertTrue(x509.getNotAfter().after(new java.util.Date()));
		SSLContext serverSsl = ArenaOverlayHttpsMaterial.sslContextFromPkcs12(Files.readAllBytes(keystore), password);
		assertNotNull(serverSsl);
	}

	@Test
	void httpsRoutesAndSecurityAndLifecycle() throws Exception {
		char[] password = "test-pass-2".toCharArray();
		Path keystore = createPkcs12(new String(password));
		byte[] pkcs12 = Files.readAllBytes(keystore);
		SSLContext serverSsl = ArenaOverlayHttpsMaterial.sslContextFromPkcs12(pkcs12, password);
		SSLContext clientSsl = trustContextFromPkcs12(keystore, password);

		int port = freePort();
		ArenaOverlayHttpServer.startHttpsForTest("127.0.0.1", port, "localhost", serverSsl);
		assertTrue(ArenaOverlayHttpServer.isRunning());
		assertTrue(ArenaOverlayHttpServer.isRunningHttps());
		assertEquals(1, ArenaOverlayHttpServer.getInstanceCount());

		ArenaOverlayHttpServer.startHttpsForTest("127.0.0.1", port, "localhost", serverSsl);
		assertEquals("alreadyRunning", ArenaOverlayHttpServer.getLastStartResult());
		assertEquals(1, ArenaOverlayHttpServer.getInstanceCount());

		String base = "https://127.0.0.1:" + port;
		assertEquals(200, httpsGet(clientSsl, base + "/arena/health").statusCode());
		assertEquals(200, httpsGet(clientSsl, base + "/arena/overlay-state").statusCode());
		assertEquals(200, httpsGet(clientSsl, base + "/overlay/tiktok").statusCode());
		assertEquals(200, httpsGet(clientSsl, base + "/overlay/tiktok/").statusCode());
		assertEquals(200, httpsGet(clientSsl, base + "/overlay/tiktok?background=chroma").statusCode());
		assertEquals(200, httpsGet(clientSsl, base + "/overlay/tiktok?background=transparent").statusCode());

		HttpResponse<String> chromaPage = httpsGet(clientSsl, base + "/overlay/tiktok/tiktok.js");
		assertEquals(200, chromaPage.statusCode());
		assertTrue(chromaPage.body().contains("resolveBackgroundMode"));
		assertTrue(chromaPage.body().contains("coreHp"));
		HttpResponse<String> cssPage = httpsGet(clientSsl, base + "/overlay/tiktok/tiktok.css");
		assertEquals(200, cssPage.statusCode());
		assertTrue(cssPage.body().contains("#FF00FF"));
		assertFalse(cssPage.body().contains("transform: scale("));
		assertFalse(cssPage.body().contains("#00FF00"));

		int gift = httpsPost(clientSsl, base + "/arena/gift").statusCode();
		int chat = httpsPost(clientSsl, base + "/arena/chat").statusCode();
		int s2eGift = httpsPost(clientSsl, base + "/arena/streamtoearn/gift").statusCode();
		int s2eChat = httpsPost(clientSsl, base + "/arena/streamtoearn/chat").statusCode();
		assertTrue(gift == 404 || gift == 405, "gift=" + gift);
		assertTrue(chat == 404 || chat == 405, "chat=" + chat);
		assertTrue(s2eGift == 404 || s2eGift == 405, "s2eGift=" + s2eGift);
		assertTrue(s2eChat == 404 || s2eChat == 405, "s2eChat=" + s2eChat);

		ArenaOverlayHttpServer.stopForTest();
		assertFalse(ArenaOverlayHttpServer.isRunning());
		assertEquals(0, ArenaOverlayHttpServer.getInstanceCount());

		ArenaOverlayHttpServer.startHttpsForTest("127.0.0.1", port, "localhost", serverSsl);
		assertTrue(ArenaOverlayHttpServer.isRunning());
		assertEquals(200, httpsGet(clientSsl, base + "/arena/health").statusCode());
	}

	@Test
	void repositoryJarHasNoPrivateKeyMaterial() throws Exception {
		Path libs = Path.of("build/libs");
		if (!Files.isDirectory(libs)) {
			return;
		}
		try (Stream<Path> stream = Files.list(libs)) {
			Path jar = stream
					.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(p -> !p.getFileName().toString().contains("sources"))
					.findFirst()
					.orElse(null);
			if (jar == null) {
				return;
			}
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String name = entry.getName().toLowerCase(Locale.ROOT);
					assertFalse(name.endsWith(".p12"), name);
					assertFalse(name.endsWith(".pfx"), name);
					assertFalse(name.endsWith(".pem"), name);
					assertFalse(name.endsWith(".key"), name);
					assertFalse(name.contains("keystore.pass"), name);
					assertFalse(name.contains("cloudflare"), name);
					assertFalse(name.contains("firebase"), name);
					assertFalse(name.contains("tunnel-token"), name);
				}
			}
		}
	}
}
