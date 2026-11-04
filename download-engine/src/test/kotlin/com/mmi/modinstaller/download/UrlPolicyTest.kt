package com.mmi.modinstaller.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quali indirizzi si possono scaricare: solo https, siti noti o approvati, niente scorciatoie pericolose. */
class UrlPolicyTest {

    private fun policy(approved: Set<String> = emptySet(), insecure: Set<String> = emptySet()) =
        UrlPolicy(userHosts = { approved }, insecureHosts = insecure)

    private fun allowed(p: UrlPolicy, url: String) = p.check(url) is UrlVerdict.Allowed
    private fun rejected(p: UrlPolicy, url: String) = p.check(url) is UrlVerdict.Rejected
    private fun needsConsent(p: UrlPolicy, url: String): String? = (p.check(url) as? UrlVerdict.HostNotAllowed)?.host

    @Test
    fun `i siti noti sono ammessi`() {
        val p = policy()
        assertTrue(allowed(p, "https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar"))
        assertTrue(allowed(p, "https://github.com/user/repo/releases/download/v1/a.mcpack"))
        assertTrue(allowed(p, "https://objects.githubusercontent.com/github-production-release-asset/1"))
        assertTrue(allowed(p, "https://edge.forgecdn.net/files/1/2/mod.jar"))
    }

    @Test
    fun `solo https - http è rifiutato con la spiegazione`() {
        val p = policy()
        val v = p.check("http://cdn.modrinth.com/a.jar")
        assertTrue(v is UrlVerdict.Rejected)
        assertTrue((v as UrlVerdict.Rejected).message.contains("https"))
        assertTrue(rejected(p, "ftp://cdn.modrinth.com/a.jar"))
        assertTrue(rejected(p, "file:///sdcard/a.jar"))
        assertTrue(rejected(p, "javascript:alert(1)"))
        assertTrue(rejected(p, "content://media/external/file/1"))
    }

    @Test
    fun `un sito sconosciuto chiede il consenso`() {
        val p = policy()
        assertEquals("mcpedl.com", needsConsent(p, "https://mcpedl.com/download/addon.mcaddon"))
        assertEquals("www.example.org", needsConsent(p, "https://WWW.Example.org./f.zip"))
    }

    @Test
    fun `dopo l'approvazione il sito è ammesso`() {
        val p = policy(approved = setOf("mcpedl.com"))
        assertTrue(allowed(p, "https://mcpedl.com/download/addon.mcaddon"))
        // L'approvazione vale per quel nome esatto, non per i sottodomini né per nomi simili.
        assertEquals("evil.mcpedl.com", needsConsent(p, "https://evil.mcpedl.com/a.zip"))
        assertEquals("notmcpedl.com", needsConsent(p, "https://notmcpedl.com/a.zip"))
    }

    @Test
    fun `i caratteri jolly valgono per i sottodomini ma non per il dominio stesso né per nomi simili`() {
        val p = policy()
        assertTrue(allowed(p, "https://release-assets.githubusercontent.com/a"))
        assertNotNull(needsConsent(p, "https://githubusercontent.com/a"))
        assertNotNull(needsConsent(p, "https://evilgithubusercontent.com/a"))
        assertNotNull(needsConsent(p, "https://githubusercontent.com.evil.example/a"))
    }

    @Test
    fun `nome utente e password nell'indirizzo sono vietati`() {
        val p = policy()
        assertTrue(rejected(p, "https://user:pass@cdn.modrinth.com/a.jar"))
        // Il classico inganno: sembra Modrinth ma l'host vero è dopo la chiocciola.
        assertTrue(rejected(p, "https://cdn.modrinth.com@evil.example/a.jar"))
    }

    @Test
    fun `indirizzi numerici e rete locale sono vietati`() {
        val p = policy(approved = setOf("localhost", "intranet"))
        assertTrue(rejected(p, "https://127.0.0.1/a.jar"))
        assertTrue(rejected(p, "https://192.168.1.10/a.jar"))
        assertTrue(rejected(p, "https://[::1]/a.jar"))
        assertTrue(rejected(p, "https://2130706433/a.jar"))
        assertTrue(rejected(p, "https://0x7f.0.0.1/a.jar"))
        assertTrue(rejected(p, "https://localhost/a.jar"))
        assertTrue(rejected(p, "https://intranet/a.jar"))
        assertTrue(rejected(p, "https://nas.local/a.jar"))
        assertTrue(rejected(p, "https://router.lan/a.jar"))
    }

    @Test
    fun `solo la porta standard`() {
        val p = policy()
        assertTrue(allowed(p, "https://cdn.modrinth.com:443/a.jar"))
        assertTrue(rejected(p, "https://cdn.modrinth.com:8443/a.jar"))
    }

    @Test
    fun `indirizzi vuoti o malformati`() {
        val p = policy()
        assertTrue(rejected(p, ""))
        assertTrue(rejected(p, "   "))
        assertTrue(rejected(p, "cdn.modrinth.com/a.jar"))
        assertTrue(rejected(p, "https://"))
        assertTrue(rejected(p, "https:///a.jar"))
        assertTrue(rejected(p, "https://cdn modrinth.com/a.jar"))
        assertTrue(rejected(p, "https://cdn.modrinth.com/" + "a".repeat(UrlPolicy.MAX_URL_LENGTH)))
    }

    @Test
    fun `http è ammesso solo per i server di prova indicati`() {
        val p = policy(insecure = setOf("127.0.0.1:8080", "10.0.2.2:8765"))
        assertTrue(allowed(p, "http://127.0.0.1:8080/a.bin"))
        assertTrue(allowed(p, "http://10.0.2.2:8765/a.bin"))
        assertTrue(rejected(p, "http://127.0.0.1:9999/a.bin"))
        assertTrue(rejected(p, "http://10.0.2.2/a.bin"))
        assertTrue(rejected(p, "http://example.com:8080/a.bin"))
        // Senza server di prova (come nell'app vera) non c'è alcuna eccezione.
        assertTrue(rejected(policy(), "http://127.0.0.1:8080/a.bin"))
    }

    @Test
    fun `nomi di sito da approvare`() {
        assertEquals("mcpedl.com", UrlPolicy.normalizeHost("  MCPEDL.com. "))
        assertEquals("a-b.example.org", UrlPolicy.normalizeHost("a-b.example.org"))
        assertNull(UrlPolicy.normalizeHost("https://mcpedl.com/download"))
        assertNull(UrlPolicy.normalizeHost("mcpedl"))
        assertNull(UrlPolicy.normalizeHost("127.0.0.1"))
        assertNull(UrlPolicy.normalizeHost("localhost"))
        assertNull(UrlPolicy.normalizeHost("exa mple.com"))
        assertNull(UrlPolicy.normalizeHost("-bad.example.com"))
        assertNull(UrlPolicy.normalizeHost(""))
    }
}
