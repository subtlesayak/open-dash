package com.example.opendash.dash.nav.provider

object NavigationProviderPolicy {
    fun select(
        experimentalEnabled: Boolean,
        googleNavFlavor: Boolean,
        mapsApiKeyPresent: Boolean,
        googleTermsAccepted: Boolean,
    ): NavigationProviderId =
        if (experimentalEnabled && googleNavFlavor && mapsApiKeyPresent && googleTermsAccepted) {
            NavigationProviderId.GOOGLE_NAVIGATION
        } else {
            NavigationProviderId.OSRM
        }
}
