package com.kishore.kotatsu

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class DesktopMangaContext : MangaLoaderContext() {

    private val defaultUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // 1. In-memory CookieJar
    override val cookieJar: CookieJar = object : CookieJar {
        private val cookieStore = mutableMapOf<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host].orEmpty()
        }
    }

    // 2. Bootstrap client + DNS-over-HTTPS (Cloudflare 1.1.1.1) to bypass ISP DNS blocks
    private val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

//    private val cloudflareDns = DnsOverHttps.Builder()
//        .client(bootstrapClient)
//        .url("https://1.1.1".toHttpUrl())
////        .url("https://cloudflare-dns.com".toHttpUrl())
//        .bootstrapDnsHosts(
//            InetAddress.getByName("1.1.1.1"),
//            InetAddress.getByName("1.0.0.1")
//        )
//        .build()



    // 3. Main OkHttpClient using DoH + desktop User-Agent
    override val httpClient: OkHttpClient = bootstrapClient.newBuilder()
        
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", defaultUserAgent)
                .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                .build()
            chain.proceed(request)
        }
        .build()

    // 4. The bundle containing all built-in Kotatsu manga parsers
    @OptIn(InternalParsersApi::class, DirectParsersAccess::class)
    override val parsers: ParsersBundle = ParsersBundleImpl

    override fun getDefaultUserAgent(): String = defaultUserAgent

    // 5. Default configuration provider for each manga source
    override fun getConfig(source: MangaSource): MangaSourceConfig {
        return object : MangaSourceConfig {
            override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
        }
    }

    // 6. JavaScript evaluation stubs
    override suspend fun evaluateJs(script: String): String? = null

    override suspend fun evaluateJs(
        baseUrl: String,
        script: String,
        timeout: Long
    ): String? = null

    // 7. Image unscrambling stubs
    override fun redrawImageResponse(
        response: Response,
        redraw: (image: Bitmap) -> Bitmap
    ): Response = response

    override fun createBitmap(width: Int, height: Int): Bitmap {
        throw NotImplementedError("Bitmap unscrambling not yet implemented on Desktop")
    }

    fun createParser(source: ParserMangaSource) = newParserInstance(source)
}
