package com.example.northstar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.example.northstar.ui.components.CircularDash
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.AuthViewModel
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

private const val WEB_CLIENT_ID =
    "246319873644-tr50gd0sflk4rqee8hcjt3m34subj4un.apps.googleusercontent.com"

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onSignedIn: () -> Unit,
    onSkip: () -> Unit,
) {
    val authState by authViewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cmError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authState.isSignedIn) {
        if (authState.isSignedIn) onSignedIn()
    }

    fun launchGoogleSignIn() {
        cmError = null
        scope.launch {
            try {
                val credentialManager = CredentialManager.create(context)
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(
                        GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).build()
                    )
                    .build()
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    authViewModel.signInWithGoogle(googleCredential.idToken)
                }
            } catch (e: GetCredentialException) {
                cmError = e.message ?: "Google sign-in failed"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colorStops = arrayOf(0f to Color(0xFF15191B), 0.6f to Bg1),
                    center = Offset(0.5f, 0f),
                    radius = Float.MAX_VALUE,
                )
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp),
        ) {
            // Brand
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularDash(
                    size = 140.dp,
                    pan = Offset.Zero,
                    zoom = 1.05f,
                    compact = true,
                    live = true,
                )

                Spacer(Modifier.height(30.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "NORTHSTAR",
                        color = TextHi,
                        fontFamily = GeistMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        letterSpacing = 0.16.sp,
                    )
                    Spacer(Modifier.width(9.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Gold))
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Your Himalayan's co-pilot.\nRoutes to the dash, eyes on the road.",
                    color = TextMid,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 250.dp),
                )
            }

            // Auth
            Column(modifier = Modifier.padding(bottom = 40.dp)) {
                val displayError = authState.error ?: cmError
                if (displayError != null) {
                    Text(
                        displayError,
                        color = Alert,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                val loading = authState.loading
                // Google sign-in only when Firebase is configured (bring-your-own-project).
                if (authState.syncAvailable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (loading) Surf2 else Color(0xFFF8F8F8))
                            .clickable(enabled = !loading) { launchGoogleSignIn() }
                            .padding(horizontal = 20.dp),
                    ) {
                        Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (loading) "Signing in…" else "Continue with Google",
                            color = if (loading) TextMid else Color(0xFF1F1F1F),
                            fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // Always available: use the app locally without an account (no sync).
                Text(
                    if (authState.syncAvailable) "Continue without signing in" else "Continue",
                    color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surf2)
                        .clickable(enabled = !loading) { onSkip() }
                        .wrapContentHeight(Alignment.CenterVertically),
                )

                Spacer(Modifier.height(18.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(TextLo))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (authState.syncAvailable) "Sign in to sync across devices · data stays local otherwise"
                        else "Local only · add a Firebase project to sync across devices",
                        color = TextLo, fontSize = 12.5.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
