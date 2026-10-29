package dev.routerhub.extension.tenda

/** The router's answer to a plain (not scrambled) form post. */
internal class PlainAnswer(val code: Int, val location: String?, val body: String) {
    /** The firmware ends the session by redirecting to its login page, e.g. after a password change. */
    val endsSession: Boolean get() = code in 300..399 && location.orEmpty().contains("login.html")
}

/**
 * What the settings pages need from the router: the firmware's `goform` calls with the scrambling
 * and the session handling taken care of. Tests supply a fake, the firmware supplies [TendaHttpClient].
 */
internal interface TendaApi {
    /** `GET goform/<name>`, unscrambled and parsed: a [Map] or a [List]. */
    suspend fun read(name: String): Any?

    /**
     * `POST goform/<name>` with [body] (see [Form]) scrambled the way the web UI does it.
     *
     * @throws dev.routerhub.api.CommandFailedException if the router answers with an error code.
     */
    suspend fun write(name: String, body: String)

    /**
     * `POST goform/<name>` as a plain form. The few pages that are real HTML forms (reboot, login
     * password) are not scrambled by the web UI, so they are not here either.
     */
    suspend fun postPlain(name: String, body: String): PlainAnswer
}
