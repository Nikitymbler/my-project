package com.nikita.arenaofnations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaOverlayHttpsMaterialTest {
	@TempDir
	Path tempDir;

	private Path createPkcs12(
			String alias,
			String cn,
			String sanExt,
			boolean serverAuth,
			int validityDays,
			String password) throws Exception {
		Path keystore = tempDir.resolve(alias + ".p12");
		Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
		if (!Files.isRegularFile(keytool)) {
			keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
		}
		List<String> cmd = new ArrayList<>();
		cmd.add(keytool.toString());
		cmd.add("-genkeypair");
		cmd.add("-alias");
		cmd.add(alias);
		cmd.add("-keyalg");
		cmd.add("RSA");
		cmd.add("-keysize");
		cmd.add("2048");
		cmd.add("-validity");
		cmd.add(String.valueOf(validityDays));
		cmd.add("-dname");
		cmd.add("CN=" + cn);
		if (sanExt != null && !sanExt.isBlank()) {
			cmd.add("-ext");
			cmd.add(sanExt);
		}
		if (serverAuth) {
			cmd.add("-ext");
			cmd.add("EKU=serverAuth");
		}
		cmd.add("-storetype");
		cmd.add("PKCS12");
		cmd.add("-keystore");
		cmd.add(keystore.toAbsolutePath().toString());
		cmd.add("-storepass");
		cmd.add(password);
		cmd.add("-keypass");
		cmd.add(password);
		ProcessBuilder builder = new ProcessBuilder(cmd);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), "keytool failed: " + output);
		return keystore;
	}

	private static LoadedCert loadFirst(Path keystore, char[] password) throws Exception {
		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(keystore)) {
			ks.load(in, password);
		}
		Enumeration<String> aliases = ks.aliases();
		while (aliases.hasMoreElements()) {
			String alias = aliases.nextElement();
			if (!ks.isKeyEntry(alias)) {
				continue;
			}
			Certificate cert = ks.getCertificate(alias);
			PrivateKey key = (PrivateKey) ks.getKey(alias, password);
			return new LoadedCert(alias, (X509Certificate) cert, key, ks);
		}
		throw new IllegalStateException("no key entry");
	}

	private record LoadedCert(String alias, X509Certificate cert, PrivateKey key, KeyStore store) {
	}

	@Test
	void selectionRequiresUsableSanServerAuthAndPrivateKey() throws Exception {
		char[] password = "sel-pass".toCharArray();
		Date now = new Date();
		String fullSan = "SAN=dns:localhost,dns:arena-overlay.test,ip:127.0.0.1";

		LoadedCert good = loadFirst(createPkcs12("good", "localhost", fullSan, true, 365, "sel-pass"), password);
		assertTrue(ArenaOverlayHttpsMaterial.isCandidateUsable(good.cert(), true, now));
		assertTrue(ArenaOverlayHttpsMaterial.hasFullLoopbackSan(good.cert()));
		assertEquals(3, ArenaOverlayHttpsMaterial.sanCompletenessScore(good.cert()));

		LoadedCert wrongSan = loadFirst(
				createPkcs12("wrong", "other.host", "SAN=dns:other.host", true, 365, "sel-pass"), password);
		assertFalse(ArenaOverlayHttpsMaterial.isCandidateUsable(wrongSan.cert(), true, now));

		LoadedCert noEku = loadFirst(createPkcs12("noeku", "localhost", fullSan, false, 365, "sel-pass"), password);
		assertFalse(ArenaOverlayHttpsMaterial.isCandidateUsable(noEku.cert(), true, now));

		assertFalse(ArenaOverlayHttpsMaterial.isCandidateUsable(good.cert(), false, now));

		Date afterExpiry = new Date(good.cert().getNotAfter().getTime() + 86_400_000L);
		assertFalse(ArenaOverlayHttpsMaterial.isCandidateUsable(good.cert(), true, afterExpiry));
	}

	@Test
	void selectionPrefersFullSanThenFreshest() throws Exception {
		char[] password = "pick-pass".toCharArray();
		LoadedCert partial = loadFirst(
				createPkcs12(
						"partial",
						"arena-overlay.test",
						"SAN=dns:arena-overlay.test",
						true,
						700,
						"pick-pass"),
				password);
		LoadedCert full = loadFirst(
				createPkcs12(
						"full",
						"localhost",
						"SAN=dns:localhost,dns:arena-overlay.test,ip:127.0.0.1",
						true,
						30,
						"pick-pass"),
				password);

		List<ArenaOverlayHttpsMaterial.SelectedCertificate> candidates = List.of(
				new ArenaOverlayHttpsMaterial.SelectedCertificate(
						"partial", partial.cert(), partial.key(), partial.store(), 2),
				new ArenaOverlayHttpsMaterial.SelectedCertificate(
						"full", full.cert(), full.key(), full.store(), 2));

		ArenaOverlayHttpsMaterial.SelectedCertificate best = ArenaOverlayHttpsMaterial.selectBestCandidate(candidates);
		assertNotNull(best);
		assertEquals("full", best.alias());
	}

	@Test
	void primaryUrlsUseLocalhostAndLegacyAliasRemains() {
		assertTrue(ArenaOverlayHttpServer.getLocalTikTokUrl().startsWith("https://localhost:"));
		assertTrue(ArenaOverlayHttpServer.getLocalTikTokUrl().endsWith("/overlay/tiktok"));
		assertTrue(ArenaOverlayHttpServer.getChromaOverlayUrl().contains("background=chroma"));
		assertTrue(ArenaOverlayHttpServer.getTransparentOverlayUrl().contains("background=transparent"));
		assertTrue(ArenaOverlayHttpServer.getLocalPreviewUrl().contains("background=chroma"));
		assertTrue(ArenaOverlayHttpServer.getLocalPreviewUrl().contains("preview=1"));
		assertTrue(ArenaOverlayHttpServer.getLegacyAliasUrl().contains("arena-overlay.test"));
		assertEquals("localhost", ArenaOverlayHttpsMaterial.PRIMARY_HOSTNAME);
		assertFalse(ArenaOverlayHttpsMaterial.inspect().customHostsRequired());
		assertTrue(ArenaOverlayHttpsMaterial.inspect().proxyIndependentPrimaryUrl());
	}

	@Test
	void sslContextFromUsablePkcs12() throws Exception {
		char[] password = "ssl-pass".toCharArray();
		Path keystore = createPkcs12(
				"ssl",
				"localhost",
				"SAN=dns:localhost,dns:arena-overlay.test,ip:127.0.0.1",
				true,
				365,
				"ssl-pass");
		SSLContext context = ArenaOverlayHttpsMaterial.sslContextFromPkcs12(Files.readAllBytes(keystore), password);
		assertNotNull(context);
	}

	@Test
	void damagedLegacyDoesNotForceDpapiUnlockFailure() throws Exception {
		Path runtime = tempDir.resolve("overlay-https");
		Files.createDirectories(runtime);
		Files.writeString(runtime.resolve(ArenaOverlayHttpsMaterial.PASSWORD_DPAPI_FILE), "not-a-dpapi-blob");
		Files.write(runtime.resolve(ArenaOverlayHttpsMaterial.KEYSTORE_FILE), new byte[] {1, 2, 3});
		String previous = System.getProperty("arena.overlay.https.runtimeDir");
		System.setProperty("arena.overlay.https.runtimeDir", runtime.toAbsolutePath().toString());
		try {
			ArenaOverlayHttpsMaterial.CertificateStatus status = ArenaOverlayHttpsMaterial.inspect();
			assertFalse(status.error() != null && status.error().toLowerCase().contains("unable to unlock"));

			try {
				SSLContext context = ArenaOverlayHttpsMaterial.loadSslContext();
				assertNotNull(context);
				assertEquals("WINDOWS_MY", ArenaOverlayHttpsMaterial.inspect().certificateSource());
			} catch (Exception thrown) {
				String message = thrown.getMessage() == null ? "" : thrown.getMessage();
				assertFalse(
						message.toLowerCase().contains("unable to unlock local overlay keystore credentials"),
						"legacy DPAPI must not be the hard failure: " + message);
				boolean clear =
						message.contains("CurrentUser\\My")
								|| message.contains("SunMSCAPI")
								|| message.contains("not configured")
								|| message.contains("localhost");
				assertTrue(clear, "unexpected message: " + message);
			}
		} finally {
			if (previous == null) {
				System.clearProperty("arena.overlay.https.runtimeDir");
			} else {
				System.setProperty("arena.overlay.https.runtimeDir", previous);
			}
		}
	}

	@Test
	void inspectDoesNotRequireLegacyWhenWindowsMyPathPreferred() {
		ArenaOverlayHttpsMaterial.CertificateStatus status = ArenaOverlayHttpsMaterial.inspect();
		assertFalse(status.legacyPkcs12Required());
		assertFalse(status.legacyDpapiRequired());
		assertFalse(status.customHostsRequired());
		assertEquals("localhost", status.primaryHostname());
		assertEquals("127.0.0.1", status.primaryLoopbackAddress());
		if ("WINDOWS_MY".equals(status.certificateSource())) {
			assertTrue(status.certificateConfigured());
			assertTrue(status.privateKeyAvailable());
		}
	}
}
