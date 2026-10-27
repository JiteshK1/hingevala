package com.hingevala

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.File
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The phone's own adb, reached over Wireless debugging the way Shizuku does it. Gives the
 * app the same shell access a computer has over USB, which is what starting uiautomator
 * needs. Pair once (the phone then trusts this app's key); after that, turning on
 * Wireless debugging is enough to connect.
 */
class Adb private constructor(ctx: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        api = Build.VERSION.SDK_INT
        // Never wait forever, e.g. on an "Allow USB debugging?" prompt nobody answers.
        setTimeout(30, TimeUnit.SECONDS)
        val keyFile = File(ctx.filesDir, "adb.key")
        val certFile = File(ctx.filesDir, "adb.crt")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            key = pair.private
            cert = selfSigned(pair.public, pair.private)
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey() = key
    override fun getCertificate() = cert
    override fun getDeviceName() = "Hingevala"

    companion object {
        private const val END_MARK = "__hingevala_done__"
        private const val LOCAL_PORT = 5555
        private const val ADB_WIFI = "adb_wifi_enabled"

        fun wirelessDebuggingOn(ctx: Context): Boolean =
            android.provider.Settings.Global.getInt(ctx.contentResolver, ADB_WIFI, 0) == 1

        /** Granted through adb on the first connection; lets the app turn Wireless debugging on. */
        fun canChangeSettings(ctx: Context): Boolean =
            ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        @Volatile
        private var instance: Adb? = null

        fun get(ctx: Context): Adb = instance ?: synchronized(this) {
            instance ?: Adb(ctx.applicationContext).also { instance = it }
        }

        private fun selfSigned(public: java.security.PublicKey, private: PrivateKey): Certificate {
            val algorithm = "SHA512withRSA"
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 3650L * 86_400_000) // 10 years
            val name = X500Name("CN=Hingevala")
            val extensions = CertificateExtensions().apply {
                set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(public).identifier))
                set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            }
            val info = X509CertInfo().apply {
                set("version", CertificateVersion(2))
                set("serialNumber", CertificateSerialNumber(Random.nextInt() and Int.MAX_VALUE))
                set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
                set("subject", CertificateSubjectName(name))
                set("key", CertificateX509Key(public))
                set("validity", CertificateValidity(notBefore, notAfter))
                set("issuer", CertificateIssuerName(name))
                set("extensions", extensions)
            }
            return X509CertImpl(info).apply { sign(private, algorithm) }
        }
    }

    /** The port Wireless debugging's "Pair device with pairing code" dialog is listening
     * on, found on the network (the dialog shows it too). Null if no dialog is open. */
    fun findPairingPort(ctx: Context, timeoutMs: Long = 15_000): Int? = discover(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs)

    /**
     * Connect if not connected yet, trying in turn:
     * 1. adb listening on the phone itself (port 5555). Works on mobile data with Wi-Fi off,
     *    until the phone restarts.
     * 2. Wireless debugging (needs Wi-Fi). If it's off and the app has been allowed to
     *    change it, it's switched on first. Once connected this way, adb is moved to (1)
     *    as well, so the next connections don't need Wi-Fi.
     */
    @Synchronized
    fun ensureConnected(ctx: Context): Boolean {
        if (isConnected) return true
        if (connectLocal()) return true
        if (!wirelessDebuggingOn(ctx) && canChangeSettings(ctx)) {
            BotLog.log("Turning on Wireless debugging…")
            runCatching { android.provider.Settings.Global.putInt(ctx.contentResolver, ADB_WIFI, 1) }
        }
        val port = discover(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 15_000) ?: return false
        if (!connect("127.0.0.1", port)) return false
        grant(ctx)
        moveToLocalPort(ctx)
        return true
    }

    /** Permissions adb can give the app, so nothing needs allowing by hand: turning
     * Wireless debugging on (for later starts), the floating STOP button, no battery
     * optimisation, notifications. Instant and harmless to repeat; done on every start. */
    fun grant(ctx: Context) {
        val pkg = ctx.packageName
        shell(
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS; " +
                "appops set $pkg SYSTEM_ALERT_WINDOW allow; " +
                "dumpsys deviceidle whitelist +$pkg >/dev/null; " +
                "pm grant $pkg android.permission.POST_NOTIFICATIONS",
        )
    }

    /** adb on port 5555 too, so the bot keeps working with Wi-Fi off (until a restart). */
    private fun moveToLocalPort(ctx: Context) {
        if (!localPortOpen()) {
            BotLog.log("Switching adb to work without Wi-Fi (until the phone restarts).")
            // adb restarts in this mode, which drops the Wireless debugging connection.
            runCatching { openStream("tcpip:$LOCAL_PORT") }
            runCatching { disconnect() }
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && !connectLocal()) Thread.sleep(1000)
            if (!isConnected) {
                // Not fatal: Wireless debugging still works while Wi-Fi is on.
                BotLog.log("adb didn't come back on port $LOCAL_PORT; staying on Wireless debugging.")
                val port = discover(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 15_000)
                if (port != null) connect("127.0.0.1", port)
            }
        }
    }

    /** adb on this phone at 127.0.0.1:5555, if it's listening there. */
    private fun connectLocal(): Boolean {
        if (!localPortOpen()) return false
        return try {
            connect("127.0.0.1", LOCAL_PORT)
        } catch (e: Exception) {
            BotLog.log("adb on port $LOCAL_PORT refused the connection (${e.message}).")
            false
        }
    }

    private fun localPortOpen(): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", LOCAL_PORT), 500) }
        true
    } catch (_: Exception) {
        false
    }

    /** True if connected and adb still answers; a dead connection can still say connected. */
    fun alive(): Boolean = isConnected && try {
        shell("echo ok", 5_000) == "ok"
    } catch (_: Exception) {
        false
    }

    /** Whether the bot can connect right now without the user doing anything. */
    fun quickCheck(ctx: Context): String = when {
        isConnected -> "connected"
        localPortOpen() && (Pairing.isPaired(ctx) || canChangeSettings(ctx)) -> "ready (works on mobile data)"
        wirelessDebuggingOn(ctx) -> "ready (Wireless debugging is on)"
        canChangeSettings(ctx) -> "ready (needs Wi-Fi: Start turns Wireless debugging on)"
        else -> "Wireless debugging is off"
    }

    /** Output of a shell command, run as the shell user (like `adb shell`). libadb doesn't
     * reliably report when a command's output has ended, so the command echoes a marker
     * when it's done and reading stops there. */
    fun shell(command: String, timeoutMs: Long = 30_000): String {
        val stream = openStream("shell:($command); echo $END_MARK")
        val input = stream.openInputStream()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                val ready = try {
                    input.available()
                } catch (_: java.io.IOException) {
                    break // closed
                }
                if (ready > 0) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.toString(Charsets.UTF_8.name()).trimEnd().endsWith(END_MARK)) break
                } else {
                    Thread.sleep(5)
                }
            }
        } finally {
            runCatching { stream.close() }
        }
        return out.toString(Charsets.UTF_8.name()).trimEnd().removeSuffix(END_MARK).trim()
    }

    /** A long-running shell command whose stream stays open (it ends when closed). */
    fun shellStream(command: String): AdbStream = openStream("shell:$command")

    private fun discover(ctx: Context, type: String, timeoutMs: Long): Int? {
        var found: Int? = null
        val done = CountDownLatch(1)
        val mdns = AdbMdns(ctx, type) { _: InetAddress?, port: Int ->
            if (port > 0) {
                found = port
                done.countDown()
            }
        }
        mdns.start()
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        return found
    }
}
