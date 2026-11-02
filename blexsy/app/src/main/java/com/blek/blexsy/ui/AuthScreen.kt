package com.blek.blexsy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import com.blek.blexsy.data.BlexsyApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun AuthScreen(
    api: BlexsyApiClient,
    initialSignup: Boolean = false,
    suggestedName: String? = null,
    onBack: (() -> Unit)? = null,
    onAuthenticated: (token: String?) -> Unit,
    onContinueGuest: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptic = rememberBlexsyHaptic()
    val poppins = LocalBlexsyFontFamily.current
    var isSignup by remember { mutableStateOf(initialSignup) }
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var otpStep by remember { mutableStateOf(false) }
    var cooldown by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val invalidEmailError = stringResource(R.string.auth_error_invalid_email)
    val emptyNameError = stringResource(R.string.auth_error_empty_name)
    val codeLengthError = stringResource(R.string.auth_error_code_length)
    val resendFailedError = stringResource(R.string.auth_error_resend_failed)
    val sendFailedError = stringResource(R.string.auth_error_send_failed)
    val verifyFailedError = stringResource(R.string.auth_error_verify_failed)

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141414))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = if (onBack != null) 12.dp else 28.dp, bottom = 16.dp)
        ) {
            Text(
                text = if (otpStep) stringResource(R.string.auth_title_digits) else if (isSignup) stringResource(R.string.auth_title_signup) else stringResource(R.string.auth_title_login),
                fontFamily = poppins,
                color = Color.White,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (otpStep) {
                    stringResource(R.string.auth_desc_digits, email.trim())
                } else {
                    stringResource(R.string.auth_desc_login)
                },
                fontFamily = poppins,
                color = Color(0xFF8C8C8C),
                fontSize = 15.sp,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (!otpStep) {
                BlexsyTabs(
                    items = listOf(false to stringResource(R.string.auth_tab_login), true to stringResource(R.string.auth_tab_signup)),
                    selected = isSignup,
                    onSelect = {
                        if (!isLoading) {
                            isSignup = it
                            errorMsg = null
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            if (errorMsg != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2A1515))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = errorMsg!!,
                        fontFamily = poppins,
                        color = Color(0xFFFF6B6B),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (!otpStep) {
                if (isSignup) {
                    BlexsyInput(
                        value = name,
                        onValueChange = {
                            name = it
                            errorMsg = null
                        },
                        label = stringResource(R.string.auth_label_name),
                        placeholder = stringResource(R.string.auth_placeholder_name),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!suggestedName.isNullOrBlank() && name != suggestedName) {
                        Spacer(modifier = Modifier.height(8.dp))
                        BlexsyChip(
                            text = suggestedName,
                            leadingText = stringResource(R.string.auth_chip_local_name),
                            icon = Icons.Rounded.Person,
                            onClick = {
                                name = suggestedName
                                errorMsg = null
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }

                BlexsyInput(
                    value = email,
                    onValueChange = {
                        email = it
                        errorMsg = null
                    },
                    label = stringResource(R.string.auth_label_email),
                    placeholder = stringResource(R.string.auth_placeholder_email),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                BlexsyInput(
                    value = otpCode,
                    onValueChange = {
                        if (it.length <= 6 && it.all { ch -> ch.isDigit() }) {
                            otpCode = it
                            errorMsg = null
                        }
                    },
                    label = stringResource(R.string.auth_label_otp),
                    placeholder = stringResource(R.string.auth_placeholder_otp),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            otpStep = false
                            otpCode = ""
                            errorMsg = null
                        },
                        enabled = !isLoading
                    ) {
                        Text(
                            text = stringResource(R.string.auth_wrong_email),
                            fontFamily = poppins,
                            color = Color(0xFF8C8C8C),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    TextButton(
                        onClick = {
                            if (cooldown <= 0 && !isLoading) {
                                scope.launch {
                                    isLoading = true
                                    errorMsg = null
                                    try {
                                        api.requestOtp(email.trim(), if (isSignup) name.trim() else null)
                                        cooldown = 60
                                    } catch (e: Exception) {
                                        errorMsg = e.message ?: resendFailedError
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        enabled = !isLoading && cooldown <= 0
                    ) {
                        Text(
                            text = if (cooldown > 0) stringResource(R.string.auth_resend_cooldown, cooldown) else stringResource(R.string.auth_resend_code),
                            fontFamily = poppins,
                            color = if (cooldown > 0) Color(0xFF666666) else BlexsyColors.Primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Pinned Footer matching Onboarding
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BlexsyButton(
                text = if (otpStep) stringResource(R.string.auth_button_lock_in) else stringResource(R.string.auth_button_send_code),
                onClick = {
                    if (!otpStep) {
                        val cleanEmail = email.trim()
                        if (cleanEmail.isEmpty() || !cleanEmail.contains('@')) {
                            errorMsg = invalidEmailError
                            return@BlexsyButton
                        }
                        if (isSignup && name.trim().isEmpty()) {
                            errorMsg = emptyNameError
                            return@BlexsyButton
                        }
                        scope.launch {
                            isLoading = true
                            errorMsg = null
                            try {
                                api.requestOtp(cleanEmail, if (isSignup) name.trim() else null)
                                otpStep = true
                                cooldown = 60
                            } catch (e: Exception) {
                                errorMsg = e.message ?: sendFailedError
                            } finally {
                                isLoading = false
                            }
                        }
                    } else {
                        val code = otpCode.trim()
                        if (code.length != 6) {
                            errorMsg = codeLengthError
                            return@BlexsyButton
                        }
                        scope.launch {
                            isLoading = true
                            errorMsg = null
                            try {
                                val res = api.verifyOtp(email.trim(), code)
                                val token = res["token"]?.jsonPrimitive?.content
                                api.token = token
                                onAuthenticated(token)
                            } catch (e: Exception) {
                                errorMsg = e.message ?: verifyFailedError
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                variant = ButtonVariant.Primary,
                size = ButtonSize.Lg,
                isLoading = isLoading,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.auth_button_guest),
                fontFamily = poppins,
                color = Color(0xFF8C8C8C),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onContinueGuest)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
