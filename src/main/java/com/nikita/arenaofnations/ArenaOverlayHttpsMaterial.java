package com.nikita.arenaofnations;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;

/**
 * Local HTTPS material for the overlay server.
 * Primary path: Windows CurrentUser\\My via SunMSCAPI ({@code Windows-MY}).
 * Primary public hostname is {@code localhost}; {@code arena-overlay.test} is a legacy alias.
 */
public final class ArenaOverlayHttpsMaterial {
	public static final String PRIMARY_HOSTNAME = "localhost";
	public static final String LEGACY_HOSTNAME = "arena-overlay.test";
	public static final String LOOPBACK_IP = "127.0.0.1";
	/** @deprecated Use {@link #PRIMARY_HOSTNAME}. Kept for older call sites. */
	@Deprecated
	public static final String DEFAULT_HOSTNAME = PRIMARY_HOSTNAME;
	public static final String KEYSTORE_FILE = "server.p12";
	public static final String PASSWORD_DPAPI_FILE = "keystore.pass.dpapi";
	public static final String SERVER_CERT_FILE = "server.cer";
	public static final String ROOT_CERT_FILE = "root-ca.cer";
	public static final String HOSTS_MARKER = LEGACY_HOSTNAME;
	public static final String EXPECTED_FRIENDLY_SERVER = "ArenaOfNations-Overlay-Server";
	public static final String EXPECTED_ROOT_SUBJECT = "CN=Arena of Nations Overlay Local CA";
	private static final String SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1";

	public enum CertificateSource {
		WINDOWS_MY,
		LEGACY_PKCS12,
		NONE
	}

	private ArenaOverlayHttpsMaterial() {
	}

	public static Path runtimeDir() {
		String override = System.getProperty("arena.overlay.https.runtimeDir");
		if (override != null && !override.isBlank()) {
			return Path.of(override.trim());
		}
		String localAppData = System.getenv("LOCALAPPDATA");
		if (localAppData == null || localAppData.isBlank()) {
			localAppData = System.getProperty("user.home", ".") + "\\AppData\\Local";
		}
		return Path.of(localAppData, "ArenaOfNations", "overlay-https");
	}

	public static Path keystorePath() {
		return runtimeDir().resolve(KEYSTORE_FILE);
	}

	public static Path passwordDpapiPath() {
		return runtimeDir().resolve(PASSWORD_DPAPI_FILE);
	}

	public static Path serverCertPath() {
		return runtimeDir().resolve(SERVER_CERT_FILE);
	}

	public static Path rootCertPath() {
		return runtimeDir().resolve(ROOT_CERT_FILE);
	}

	/** @deprecated Prefer {@link #isHttpsMaterialAvailable()}. */
	@Deprecated
	public static boolean isKeystorePresent() {
		return isHttpsMaterialAvailable();
	}

	public static boolean isSunMscapiAvailable() {
		return Security.getProvider("SunMSCAPI") != null;
	}

	public static boolean isHttpsMaterialAvailable() {
		return isHttpsMaterialAvailable(PRIMARY_HOSTNAME);
	}

	public static boolean isHttpsMaterialAvailable(String ignoredExpectedHostname) {
		try {
			SelectedCertificate selected = findBestWindowsMyCertificate();
			if (selected != null) {
				return true;
			}
		} catch (Exception ignored) {
			// fall through to legacy probe without unlocking DPAPI aggressively
		}
		return Files.isRegularFile(keystorePath()) && Files.isRegularFile(passwordDpapiPath());
	}

	public static CertificateStatus inspect() {
		return inspect(PRIMARY_HOSTNAME);
	}

	public static CertificateStatus inspect(String ignoredExpectedHostname) {
		boolean legacyHosts = isHostsMappingValid(LEGACY_HOSTNAME);
		boolean trusted = isRootTrusted();
		boolean mscapi = isSunMscapiAvailable();
		boolean storeLoaded = false;
		int matching = 0;
		String selectedAlias = "-";
		CertificateSource source = CertificateSource.NONE;
		boolean localhostValid = false;
		boolean ipValid = false;
		boolean legacyValid = false;
		boolean serverAuth = false;
		boolean privateKeyOk = false;
		boolean configured = false;
		String expiresAt = "-";
		String error = "";

		try {
			SelectedCertificate selected = findBestWindowsMyCertificate();
			storeLoaded = mscapi;
			if (selected != null) {
				matching = selected.matchingCount();
				selectedAlias = sanitizeAlias(selected.alias());
				source = CertificateSource.WINDOWS_MY;
				X509Certificate cert = selected.certificate();
				localhostValid = certificateMatchesHostname(cert, PRIMARY_HOSTNAME);
				ipValid = certificateMatchesHostname(cert, LOOPBACK_IP);
				legacyValid = certificateMatchesHostname(cert, LEGACY_HOSTNAME);
				serverAuth = hasServerAuthEku(cert);
				expiresAt = formatExpiry(cert.getNotAfter());
				privateKeyOk = selected.privateKey() != null;
				configured = privateKeyOk
						&& serverAuth
						&& cert.getNotAfter().after(new Date())
						&& (localhostValid || ipValid || legacyValid);
				if (!configured && error.isBlank()) {
					error = "windows_my_certificate_incomplete";
				}
			} else if (mscapi) {
				storeLoaded = true;
				error = "No usable localhost/loopback certificate in Windows CurrentUser\\My";
			}
		} catch (Exception e) {
			if (error.isBlank()) {
				error = "windows_my_unavailable:" + e.getClass().getSimpleName();
			}
		}

		if (!configured) {
			boolean legacyPresent = Files.isRegularFile(keystorePath()) && Files.isRegularFile(passwordDpapiPath());
			if (source == CertificateSource.NONE && legacyPresent) {
				X509Certificate fileCert = loadExportedServerCertificateQuietly();
				if (fileCert != null) {
					localhostValid = certificateMatchesHostname(fileCert, PRIMARY_HOSTNAME);
					ipValid = certificateMatchesHostname(fileCert, LOOPBACK_IP);
					legacyValid = certificateMatchesHostname(fileCert, LEGACY_HOSTNAME);
					serverAuth = hasServerAuthEku(fileCert);
					expiresAt = formatExpiry(fileCert.getNotAfter());
				}
				if (error.isBlank()) {
					error = "legacy_pkcs12_present_but_windows_my_required";
				}
			} else if (source == CertificateSource.NONE && error.isBlank()) {
				error = "certificate_not_configured";
			}
		}

		boolean hostnameValid = localhostValid || ipValid || legacyValid;
		return new CertificateStatus(
				configured,
				trusted,
				hostnameValid,
				localhostValid,
				ipValid,
				legacyValid,
				serverAuth,
				expiresAt,
				legacyHosts,
				privateKeyOk,
				source.name(),
				mscapi,
				storeLoaded,
				matching,
				selectedAlias,
				source == CertificateSource.LEGACY_PKCS12,
				source == CertificateSource.LEGACY_PKCS12,
				true,
				PRIMARY_HOSTNAME,
				LOOPBACK_IP,
				false,
				legacyHosts,
				error);
	}

	public static SSLContext loadSslContext() throws Exception {
		return loadSslContext(PRIMARY_HOSTNAME);
	}

	public static SSLContext loadSslContext(String ignoredExpectedHostname) throws Exception {
		Exception windowsError = null;
		if (isSunMscapiAvailable()) {
			try {
				SelectedCertificate selected = findBestWindowsMyCertificate();
				if (selected != null) {
					ArenaOfNations.LOGGER.info(
							"Overlay HTTPS using Windows-MY certificate alias={}",
							sanitizeAlias(selected.alias()));
					return buildSslContextForAlias(selected.alias(), selected.keyStore());
				}
				windowsError = new IllegalStateException(
						"No usable localhost/loopback certificate in Windows CurrentUser\\My. "
								+ "Run SETUP_LOCAL_OVERLAY_HTTPS.cmd once (UAC), then restart Minecraft.");
			} catch (Exception e) {
				windowsError = e;
			}
		} else {
			windowsError = new IllegalStateException("SunMSCAPI provider is not available.");
		}

		if (Files.isRegularFile(keystorePath()) && Files.isRegularFile(passwordDpapiPath())) {
			try {
				SSLContext legacy = loadSslContextFromLegacyPkcs12(PRIMARY_HOSTNAME);
				ArenaOfNations.LOGGER.warn(
						"Overlay HTTPS using legacy PKCS12 fallback; prefer Windows-MY via SETUP_LOCAL_OVERLAY_HTTPS.cmd");
				return legacy;
			} catch (Exception legacyError) {
				ArenaOfNations.LOGGER.debug("Legacy PKCS12 fallback failed", legacyError);
			}
		}

		if (windowsError != null) {
			throw windowsError;
		}
		throw new IllegalStateException(
				"HTTPS overlay is not configured. Run SETUP_LOCAL_OVERLAY_HTTPS.cmd once (UAC).");
	}

	/** Test-only: build SSLContext from an in-memory PKCS12. */
	public static SSLContext sslContextFromPkcs12(byte[] pkcs12, char[] password) throws Exception {
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		keyStore.load(new ByteArrayInputStream(pkcs12), password);
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(keyStore, password);
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(kmf.getKeyManagers(), null, null);
		return context;
	}

	public static SelectedCertificate selectBestCandidate(List<SelectedCertificate> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		return candidates.stream()
				.max(Comparator
						.comparingInt((SelectedCertificate c) -> sanCompletenessScore(c.certificate()))
						.thenComparing((SelectedCertificate c) -> c.certificate().getNotAfter().getTime())
						.thenComparing((SelectedCertificate c) -> preferredOverlayIssuer(c.certificate()))
						.thenComparing(c -> c.alias(), String.CASE_INSENSITIVE_ORDER))
				.orElse(null);
	}

	static boolean preferredOverlayIssuer(X509Certificate cert) {
		if (cert == null) {
			return false;
		}
		String issuer = cert.getIssuerX500Principal().getName();
		return issuer != null && issuer.toLowerCase(Locale.ROOT).contains("arena of nations");
	}

	public static int sanCompletenessScore(X509Certificate cert) {
		if (cert == null) {
			return 0;
		}
		int score = 0;
		if (certificateMatchesHostname(cert, PRIMARY_HOSTNAME)) {
			score++;
		}
		if (certificateMatchesHostname(cert, LEGACY_HOSTNAME)) {
			score++;
		}
		if (certificateMatchesHostname(cert, LOOPBACK_IP)) {
			score++;
		}
		return score;
	}

	public static boolean hasFullLoopbackSan(X509Certificate cert) {
		return sanCompletenessScore(cert) >= 3;
	}

	public static boolean isCandidateUsable(X509Certificate cert, boolean hasPrivateKey, Date now) {
		if (cert == null || !hasPrivateKey || now == null) {
			return false;
		}
		if (!cert.getNotAfter().after(now)) {
			return false;
		}
		if (!hasServerAuthEku(cert)) {
			return false;
		}
		return sanCompletenessScore(cert) > 0;
	}

	/** @deprecated Prefer {@link #isCandidateUsable(X509Certificate, boolean, Date)}. */
	@Deprecated
	public static boolean isCandidateUsable(
			X509Certificate cert,
			boolean hasPrivateKey,
			String expectedHostname,
			Date now) {
		if (!isCandidateUsable(cert, hasPrivateKey, now)) {
			return false;
		}
		if (expectedHostname == null || expectedHostname.isBlank()) {
			return true;
		}
		return certificateMatchesHostname(cert, expectedHostname);
	}

	static SelectedCertificate findBestWindowsMyCertificate() throws Exception {
		return findBestWindowsMyCertificate(PRIMARY_HOSTNAME);
	}

	static SelectedCertificate findBestWindowsMyCertificate(String ignoredExpectedHostname) throws Exception {
		if (!isSunMscapiAvailable()) {
			return null;
		}
		KeyStore keyStore = KeyStore.getInstance("Windows-MY");
		keyStore.load(null, null);
		List<SelectedCertificate> matches = new ArrayList<>();
		Enumeration<String> aliases = keyStore.aliases();
		Date now = new Date();
		while (aliases.hasMoreElements()) {
			String alias = aliases.nextElement();
			if (!keyStore.isKeyEntry(alias)) {
				continue;
			}
			Certificate certificate = keyStore.getCertificate(alias);
			if (!(certificate instanceof X509Certificate x509)) {
				continue;
			}
			PrivateKey key;
			try {
				key = (PrivateKey) keyStore.getKey(alias, null);
				if (key == null) {
					key = (PrivateKey) keyStore.getKey(alias, new char[0]);
				}
			} catch (Exception e) {
				continue;
			}
			if (!isCandidateUsable(x509, key != null, now)) {
				continue;
			}
			matches.add(new SelectedCertificate(alias, x509, key, keyStore, matches.size() + 1));
		}
		SelectedCertificate best = selectBestCandidate(matches);
		if (best == null) {
			return null;
		}
		if (matches.size() > 1) {
			ArenaOfNations.LOGGER.warn(
					"Multiple usable overlay certificates in Windows-MY (count={}); selected alias={}",
					matches.size(),
					sanitizeAlias(best.alias()));
		}
		return new SelectedCertificate(best.alias(), best.certificate(), best.privateKey(), best.keyStore(), matches.size());
	}

	private static SSLContext buildSslContextForAlias(String alias, KeyStore windowsMy) throws Exception {
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(windowsMy, null);
		X509ExtendedKeyManager fixed = wrapWithFixedAlias(kmf.getKeyManagers(), alias);
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(new KeyManager[] {fixed}, null, null);
		return context;
	}

	private static X509ExtendedKeyManager wrapWithFixedAlias(KeyManager[] managers, String alias) {
		X509KeyManager delegate = null;
		for (KeyManager manager : managers) {
			if (manager instanceof X509KeyManager x509) {
				delegate = x509;
				break;
			}
		}
		if (delegate == null) {
			throw new IllegalStateException("No X509KeyManager available from Windows-MY.");
		}
		return new FixedAliasKeyManager(delegate, alias);
	}

	private static SSLContext loadSslContextFromLegacyPkcs12(String preferredHostname) throws Exception {
		char[] password = unlockPasswordLegacy();
		try (InputStream in = Files.newInputStream(keystorePath())) {
			KeyStore keyStore = KeyStore.getInstance("PKCS12");
			keyStore.load(in, password);
			String alias = findServerAlias(keyStore, preferredHostname);
			if (alias == null) {
				throw new IllegalStateException("No private key alias found in overlay keystore.");
			}
			PrivateKey key = (PrivateKey) keyStore.getKey(alias, password);
			Certificate[] chain = keyStore.getCertificateChain(alias);
			if (key == null || chain == null || chain.length == 0) {
				throw new IllegalStateException("Overlay keystore is missing private key or certificate chain.");
			}
			KeyStore use = KeyStore.getInstance("PKCS12");
			use.load(null, password);
			use.setKeyEntry(alias, key, password, chain);
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(use, password);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(kmf.getKeyManagers(), null, null);
			return context;
		} finally {
			if (password != null) {
				java.util.Arrays.fill(password, '\0');
			}
		}
	}

	public static boolean isHostsMappingValid(String hostname) {
		String host = hostname == null ? LEGACY_HOSTNAME : hostname.trim().toLowerCase(Locale.ROOT);
		if (PRIMARY_HOSTNAME.equals(host) || LOOPBACK_IP.equals(host)) {
			return true;
		}
		Path hosts = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "drivers", "etc", "hosts");
		if (!Files.isRegularFile(hosts)) {
			return false;
		}
		try {
			List<String> lines = Files.readAllLines(hosts, StandardCharsets.UTF_8);
			for (String raw : lines) {
				String line = raw.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String lower = line.toLowerCase(Locale.ROOT);
				if ((lower.startsWith("127.0.0.1") || lower.startsWith("::1"))
						&& containsHostToken(lower, host)) {
					return true;
				}
			}
		} catch (Exception ignored) {
			return false;
		}
		return false;
	}

	public static boolean isRootTrusted() {
		try {
			KeyStore roots = KeyStore.getInstance("Windows-ROOT");
			roots.load(null, null);
			Enumeration<String> aliases = roots.aliases();
			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				Certificate cert = roots.getCertificate(alias);
				if (!(cert instanceof X509Certificate x509)) {
					continue;
				}
				String subject = x509.getSubjectX500Principal().getName();
				if (subject != null && subject.contains("Arena of Nations Overlay Local CA")) {
					if (x509.getNotAfter().after(new Date())) {
						return true;
					}
				}
			}
		} catch (Exception ignored) {
			// fall through to exported CER comparison
		}
		Path cer = rootCertPath();
		if (!Files.isRegularFile(cer)) {
			return false;
		}
		try {
			X509Certificate root = loadCertificate(cer);
			KeyStore roots = KeyStore.getInstance("Windows-ROOT");
			roots.load(null, null);
			Enumeration<String> aliases = roots.aliases();
			while (aliases.hasMoreElements()) {
				String alias = aliases.nextElement();
				Certificate cert = roots.getCertificate(alias);
				if (cert instanceof X509Certificate x509 && certificatesEqual(x509, root)) {
					return true;
				}
			}
		} catch (Exception ignored) {
			return false;
		}
		return false;
	}

	static boolean certificateMatchesHostname(X509Certificate cert, String hostname) {
		if (cert == null || hostname == null || hostname.isBlank()) {
			return false;
		}
		String host = hostname.trim().toLowerCase(Locale.ROOT);
		boolean lookingForIp = LOOPBACK_IP.equals(host);
		try {
			Collection<List<?>> san = cert.getSubjectAlternativeNames();
			if (san != null) {
				for (List<?> entry : san) {
					if (entry == null || entry.size() < 2) {
						continue;
					}
					Object type = entry.get(0);
					Object value = entry.get(1);
					if (!(type instanceof Integer i)) {
						continue;
					}
					if (i == 2 && value != null
							&& host.equals(value.toString().trim().toLowerCase(Locale.ROOT))) {
						return true;
					}
					if (lookingForIp && i == 7 && ipSanMatches(value, host)) {
						return true;
					}
				}
			}
		} catch (Exception ignored) {
			// fall through to CN
		}
		if (lookingForIp) {
			return false;
		}
		String dn = cert.getSubjectX500Principal().getName();
		return dn.toLowerCase(Locale.ROOT).contains("cn=" + host);
	}

	private static boolean ipSanMatches(Object value, String expectedIp) {
		if (value == null) {
			return false;
		}
		if (value instanceof String s) {
			return expectedIp.equals(s.trim());
		}
		if (value instanceof byte[] bytes) {
			try {
				return expectedIp.equals(InetAddress.getByAddress(bytes).getHostAddress());
			} catch (Exception e) {
				return false;
			}
		}
		return expectedIp.equals(value.toString().trim());
	}

	static boolean hasServerAuthEku(X509Certificate cert) {
		try {
			List<String> eku = cert.getExtendedKeyUsage();
			if (eku == null || eku.isEmpty()) {
				return false;
			}
			return eku.contains(SERVER_AUTH_OID);
		} catch (Exception e) {
			return false;
		}
	}

	private static X509Certificate loadExportedServerCertificateQuietly() {
		try {
			if (Files.isRegularFile(serverCertPath())) {
				return loadCertificate(serverCertPath());
			}
		} catch (Exception ignored) {
			return null;
		}
		return null;
	}

	private static X509Certificate loadCertificate(Path path) throws Exception {
		try (InputStream in = Files.newInputStream(path)) {
			CertificateFactory factory = CertificateFactory.getInstance("X.509");
			Certificate cert = factory.generateCertificate(in);
			if (!(cert instanceof X509Certificate x509)) {
				throw new IllegalStateException("Not an X.509 certificate: " + path.getFileName());
			}
			return x509;
		}
	}

	private static String findServerAlias(KeyStore keyStore, String hostname) throws Exception {
		Enumeration<String> aliases = keyStore.aliases();
		String fallback = null;
		List<SelectedCertificate> usable = new ArrayList<>();
		Date now = new Date();
		while (aliases.hasMoreElements()) {
			String alias = aliases.nextElement();
			if (!keyStore.isKeyEntry(alias)) {
				continue;
			}
			if (fallback == null) {
				fallback = alias;
			}
			Certificate cert = keyStore.getCertificate(alias);
			if (!(cert instanceof X509Certificate x509)) {
				continue;
			}
			PrivateKey key = (PrivateKey) keyStore.getKey(alias, null);
			if (isCandidateUsable(x509, key != null, now)) {
				usable.add(new SelectedCertificate(alias, x509, key, keyStore, 1));
			} else if (certificateMatchesHostname(x509, hostname)) {
				return alias;
			}
		}
		SelectedCertificate best = selectBestCandidate(usable);
		return best != null ? best.alias() : fallback;
	}

	/**
	 * Legacy unlock: PowerShell ProtectedData CurrentUser, UTF-8 bytes, no entropy.
	 * Kept only for optional PKCS12 fallback — must not be required for normal start.
	 */
	private static char[] unlockPasswordLegacy() throws Exception {
		Path dpapi = passwordDpapiPath();
		if (!Files.isRegularFile(dpapi)) {
			throw new IllegalStateException("Missing protected keystore credential file.");
		}
		ProcessBuilder builder = new ProcessBuilder(
				"powershell.exe",
				"-NoProfile",
				"-ExecutionPolicy",
				"Bypass",
				"-Command",
				"$ErrorActionPreference='Stop';"
						+ "$b=[IO.File]::ReadAllBytes('" + dpapi.toAbsolutePath() + "');"
						+ "[Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect($b,$null,'CurrentUser'))");
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output;
		try (InputStream in = process.getInputStream()) {
			output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		}
		int code = process.waitFor();
		if (code != 0 || output.isBlank()) {
			throw new IllegalStateException("Unable to unlock local overlay keystore credentials.");
		}
		String first = output.lines().filter(s -> !s.isBlank()).findFirst().orElse("").trim();
		if (first.isBlank()) {
			throw new IllegalStateException("Unlocked overlay keystore password was empty.");
		}
		return first.toCharArray();
	}

	private static boolean containsHostToken(String lowerLine, String host) {
		String[] parts = lowerLine.split("\\s+");
		for (int i = 1; i < parts.length; i++) {
			if (host.equals(parts[i])) {
				return true;
			}
		}
		return false;
	}

	private static boolean certificatesEqual(X509Certificate a, X509Certificate b) {
		try {
			return java.util.Arrays.equals(a.getEncoded(), b.getEncoded());
		} catch (Exception e) {
			return false;
		}
	}

	private static String formatExpiry(Date notAfter) {
		return notAfter == null ? "-" : Instant.ofEpochMilli(notAfter.getTime()).toString();
	}

	static String sanitizeAlias(String alias) {
		if (alias == null || alias.isBlank()) {
			return "-";
		}
		String value = alias.trim();
		if (value.length() > 48) {
			return value.substring(0, 24) + "…" + value.substring(value.length() - 8);
		}
		return value;
	}

	public record SelectedCertificate(
			String alias,
			X509Certificate certificate,
			PrivateKey privateKey,
			KeyStore keyStore,
			int matchingCount) {
	}

	public record CertificateStatus(
			boolean certificateConfigured,
			boolean certificateTrusted,
			boolean certificateHostnameValid,
			boolean certificateLocalhostValid,
			boolean certificate127001Valid,
			boolean certificateLegacyAliasValid,
			boolean certificateServerAuthValid,
			String certificateExpiresAt,
			boolean hostsMappingValid,
			boolean privateKeyAvailable,
			String certificateSource,
			boolean windowsMscapiAvailable,
			boolean windowsMyStoreLoaded,
			int matchingCertificates,
			String selectedCertificateAlias,
			boolean legacyPkcs12Required,
			boolean legacyDpapiRequired,
			boolean proxyIndependentPrimaryUrl,
			String primaryHostname,
			String primaryLoopbackAddress,
			boolean customHostsRequired,
			boolean legacyHostsAliasAvailable,
			String error) {
		public boolean certificateNotExpired() {
			return certificateConfigured || certificateServerAuthValid;
		}

		public boolean readyForHttps() {
			return certificateConfigured && certificateHostnameValid && privateKeyAvailable;
		}
	}

	/** Forces HTTPS server TLS to a specific Windows-MY / keystore alias. */
	static final class FixedAliasKeyManager extends X509ExtendedKeyManager {
		private final X509KeyManager delegate;
		private final String alias;

		FixedAliasKeyManager(X509KeyManager delegate, String alias) {
			this.delegate = delegate;
			this.alias = alias;
		}

		@Override
		public String[] getClientAliases(String keyType, Principal[] issuers) {
			return delegate.getClientAliases(keyType, issuers);
		}

		@Override
		public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
			return delegate.chooseClientAlias(keyType, issuers, socket);
		}

		@Override
		public String[] getServerAliases(String keyType, Principal[] issuers) {
			return new String[] {alias};
		}

		@Override
		public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
			return alias;
		}

		@Override
		public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
			return alias;
		}

		@Override
		public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
			if (delegate instanceof X509ExtendedKeyManager extended) {
				return extended.chooseEngineClientAlias(keyType, issuers, engine);
			}
			return chooseClientAlias(keyType, issuers, null);
		}

		@Override
		public X509Certificate[] getCertificateChain(String alias) {
			return delegate.getCertificateChain(this.alias);
		}

		@Override
		public PrivateKey getPrivateKey(String alias) {
			return delegate.getPrivateKey(this.alias);
		}
	}

	/** CLI / Gradle verify entrypoint for Windows-MY SSLContext. */
	public static void main(String[] args) {
		try {
			Provider provider = Security.getProvider("SunMSCAPI");
			System.out.println("sunMSCAPIAvailable=" + (provider != null));
			System.out.println("primaryHostname=" + PRIMARY_HOSTNAME);
			System.out.println("customHostsRequired=false");
			SelectedCertificate selected = findBestWindowsMyCertificate();
			if (selected == null) {
				System.out.println("selected=none");
				System.out.println("VERIFY_WINDOWS_MY=FAILED");
				System.exit(1);
				return;
			}
			X509Certificate cert = selected.certificate();
			System.out.println("selectedAlias=" + sanitizeAlias(selected.alias()));
			System.out.println("privateKey=" + (selected.privateKey() != null));
			System.out.println("dnsSanLocalhost=" + certificateMatchesHostname(cert, PRIMARY_HOSTNAME));
			System.out.println("dnsSanLegacyAlias=" + certificateMatchesHostname(cert, LEGACY_HOSTNAME));
			System.out.println("ipSan127001=" + certificateMatchesHostname(cert, LOOPBACK_IP));
			System.out.println("serverAuth=" + hasServerAuthEku(cert));
			System.out.println("fullSan=" + hasFullLoopbackSan(cert));
			System.out.println("notAfter=" + formatExpiry(cert.getNotAfter()));
			SSLContext context = buildSslContextForAlias(selected.alias(), selected.keyStore());
			System.out.println("sslContext=" + (context != null));
			boolean ok = selected.privateKey() != null
					&& hasServerAuthEku(cert)
					&& certificateMatchesHostname(cert, PRIMARY_HOSTNAME)
					&& certificateMatchesHostname(cert, LEGACY_HOSTNAME)
					&& certificateMatchesHostname(cert, LOOPBACK_IP)
					&& context != null;
			System.out.println(ok ? "VERIFY_WINDOWS_MY=SUCCESS" : "VERIFY_WINDOWS_MY=FAILED");
			System.exit(ok ? 0 : 1);
		} catch (Exception e) {
			System.out.println("error=" + e.getClass().getSimpleName());
			System.out.println("VERIFY_WINDOWS_MY=FAILED");
			System.exit(1);
		}
	}
}
