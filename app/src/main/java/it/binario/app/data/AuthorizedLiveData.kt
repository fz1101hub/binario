package it.binario.app.data

enum class AuthorizedProviderStatus(val accessibleLabel: String) {
    NOT_CONFIGURED("Provider ferroviario autorizzato non configurato"),
    READY("Provider ferroviario autorizzato pronto"),
    UNAVAILABLE("Provider ferroviario autorizzato temporaneamente non disponibile")
}

data class AuthorizedProviderReadiness(
    val providerName: String,
    val status: AuthorizedProviderStatus,
    val hasClientCredentials: Boolean,
    val allowsNetworkRequest: Boolean,
    val message: String
)

/**
 * Client-side guardrail: a production gateway is injected only after legal and
 * technical approval. No base URL, secret, scraping rule or undocumented
 * endpoint belongs in the Android application.
 */
object AuthorizedLiveDataBoundary {
    fun trenitalia(gatewayConfigured: Boolean): AuthorizedProviderReadiness = if (gatewayConfigured) {
        AuthorizedProviderReadiness(
            providerName = "Trenitalia",
            status = AuthorizedProviderStatus.READY,
            hasClientCredentials = false,
            allowsNetworkRequest = true,
            message = "Canale autorizzato configurato tramite gateway server-side."
        )
    } else {
        AuthorizedProviderReadiness(
            providerName = "Trenitalia",
            status = AuthorizedProviderStatus.NOT_CONFIGURED,
            hasClientCredentials = false,
            allowsNetworkRequest = false,
            message = "Dati non disponibili in tempo reale: nessun canale autorizzato è configurato."
        )
    }
}
