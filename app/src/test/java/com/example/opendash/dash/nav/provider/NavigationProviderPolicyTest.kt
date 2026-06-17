package com.example.opendash.dash.nav.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationProviderPolicyTest {
    @Test
    fun defaultsToOsrmWhenExperimentIsOff() {
        assertEquals(
            NavigationProviderId.OSRM,
            NavigationProviderPolicy.select(
                experimentalEnabled = false,
                googleNavFlavor = true,
                mapsApiKeyPresent = true,
                googleTermsAccepted = true,
            ),
        )
    }

    @Test
    fun defaultsToOsrmInOssFlavor() {
        assertEquals(
            NavigationProviderId.OSRM,
            NavigationProviderPolicy.select(
                experimentalEnabled = true,
                googleNavFlavor = false,
                mapsApiKeyPresent = true,
                googleTermsAccepted = true,
            ),
        )
    }

    @Test
    fun defaultsToOsrmWhenMapsKeyIsMissing() {
        assertEquals(
            NavigationProviderId.OSRM,
            NavigationProviderPolicy.select(
                experimentalEnabled = true,
                googleNavFlavor = true,
                mapsApiKeyPresent = false,
                googleTermsAccepted = true,
            ),
        )
    }

    @Test
    fun defaultsToOsrmUntilTermsAreAccepted() {
        assertEquals(
            NavigationProviderId.OSRM,
            NavigationProviderPolicy.select(
                experimentalEnabled = true,
                googleNavFlavor = true,
                mapsApiKeyPresent = true,
                googleTermsAccepted = false,
            ),
        )
    }

    @Test
    fun selectsGoogleOnlyWhenAllGuardsAreSatisfied() {
        assertEquals(
            NavigationProviderId.GOOGLE_NAVIGATION,
            NavigationProviderPolicy.select(
                experimentalEnabled = true,
                googleNavFlavor = true,
                mapsApiKeyPresent = true,
                googleTermsAccepted = true,
            ),
        )
    }
}
