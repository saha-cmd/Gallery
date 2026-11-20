package com.gallery.feature.vault.ui

import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gallery.core.security.auth.AuthResult
import com.gallery.feature.vault.viewmodel.VaultAuthState
import com.gallery.feature.vault.viewmodel.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultAuthScreen(
    onAuthSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val pinState by viewModel.pinState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Block screenshots and screen recordings on Auth screen
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Navigate on success
    LaunchedEffect(authState) {
        if (authState is VaultAuthState.Authenticated) onAuthSuccess()
    }

    // Auto-launch biometric only if a PIN has already been set up
    LaunchedEffect(Unit) {
        viewModel.checkPinStatus()
        val hasPin = viewModel.hasExistingPin()
        if (hasPin && viewModel.biometricAuthManager.isBiometricAvailable()) {
            (context as? FragmentActivity)?.let { activity ->
                viewModel.biometricAuthManager.authenticate(activity) { result ->
                    viewModel.onBiometricResult(result)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure Vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Lock icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            val titleText = when {
                pinState.isFirstTimeSetup && !pinState.isConfirming -> "Set Up Secure PIN"
                pinState.isFirstTimeSetup && pinState.isConfirming -> "Confirm PIN"
                else -> "Secure Folder"
            }
            val subtitleText = when {
                pinState.isFirstTimeSetup && !pinState.isConfirming -> "Create a 6-digit PIN to lock your Secure Folder"
                pinState.isFirstTimeSetup && pinState.isConfirming -> "Re-enter your 6-digit PIN to confirm"
                else -> "Enter your 6-digit PIN to access private media"
            }

            Text(
                text = titleText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.height(40.dp))

            // PIN dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                repeat(6) { index ->
                    val filled = index < pinState.pin.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                if (filled) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .border(
                                2.dp,
                                if (filled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                CircleShape
                            )
                    )
                }
            }

            // Error message
            pinState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // PIN numpad
            PinPad(
                onDigit = { viewModel.onPinDigitEntered(it) },
                onDelete = viewModel::onPinDelete,
                onSubmit = {
                    if (pinState.pin.length == 6) viewModel.submitPin()
                },
                onBiometric = {
                    (context as? FragmentActivity)?.let { activity ->
                        viewModel.biometricAuthManager.authenticate(activity) { result ->
                            viewModel.onBiometricResult(result)
                        }
                    }
                },
                showBiometric = !pinState.isFirstTimeSetup && viewModel.biometricAuthManager.isBiometricAvailable()
            )
        }
    }
}

@Composable
private fun PinPad(
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit,
    onBiometric: () -> Unit,
    showBiometric: Boolean
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(if (showBiometric) "BIO" else "", "0", "DEL")
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { label ->
                    when (label) {
                        "" -> Spacer(Modifier.size(72.dp))
                        "BIO" -> IconButton(
                            onClick = onBiometric,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(Icons.Outlined.Fingerprint, "Biometric", modifier = Modifier.size(32.dp))
                        }
                        "DEL" -> IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(Icons.Outlined.Backspace, "Delete", modifier = Modifier.size(28.dp))
                        }
                        else -> OutlinedButton(
                            onClick = { onDigit(label) },
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(label, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
