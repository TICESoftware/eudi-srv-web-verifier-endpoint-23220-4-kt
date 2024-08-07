/*
 * Copyright (c) 2023 European Commission
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.europa.ec.eudi.verifier.endpoint.port.input

import arrow.core.None
import arrow.core.Option
import arrow.core.raise.Raise
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.some
import eu.europa.ec.eudi.prex.PresentationSubmission
import eu.europa.ec.eudi.verifier.endpoint.domain.*
import eu.europa.ec.eudi.verifier.endpoint.domain.Presentation.RequestObjectRetrieved
import eu.europa.ec.eudi.verifier.endpoint.port.out.cfg.CreateQueryWalletResponseRedirectUri
import eu.europa.ec.eudi.verifier.endpoint.port.out.cfg.GenerateResponseCode
import eu.europa.ec.eudi.verifier.endpoint.port.out.jose.VerifyJarmJwtSignature
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.LoadPresentationByRequestId
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.StorePresentation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Clock

/**
 * Represent the Authorisation Response placed by wallet
 */
data class AuthorisationResponseTO(
    val state: String?, // this is the request_id
    val error: String? = null,
    val errorDescription: String? = null,
    val idToken: String? = null,
    val vpToken: String? = null,
    val presentationSubmission: PresentationSubmission? = null,
)

sealed interface AuthorisationResponse {

    data class DirectPost(val response: AuthorisationResponseTO) : AuthorisationResponse
    data class DirectPostJwt(val state: String?, val jarm: Jwt) : AuthorisationResponse
}

sealed interface WalletResponseValidationError {
    data object MissingState : WalletResponseValidationError
    data class PresentationDefinitionNotFound(val requestId: RequestId) : WalletResponseValidationError

    data class UnexpectedResponseMode(
        val requestId: RequestId,
        val expected: ResponseModeOption,
        val actual: ResponseModeOption,
    ) : WalletResponseValidationError

    data class PresentationNotInExpectedState(val requestId: RequestId) : WalletResponseValidationError

    data object IncorrectStateInJarm : WalletResponseValidationError
    data object MissingIdToken : WalletResponseValidationError
    data object MissingVpTokenOrPresentationSubmission : WalletResponseValidationError
}

context(Raise<WalletResponseValidationError>)
internal fun AuthorisationResponseTO.toDomain(presentation: RequestObjectRetrieved): WalletResponse {
    fun requiredIdToken(): WalletResponse.IdToken {
        ensureNotNull(idToken) { WalletResponseValidationError.MissingIdToken }
        return WalletResponse.IdToken(idToken)
    }

    fun requiredVpToken(): WalletResponse.VpToken {
        ensureNotNull(vpToken) { WalletResponseValidationError.MissingVpTokenOrPresentationSubmission }
        ensureNotNull(presentationSubmission) { WalletResponseValidationError.MissingVpTokenOrPresentationSubmission }
        return WalletResponse.VpToken(vpToken, presentationSubmission)
    }

    fun requiredIdAndVpToken(): WalletResponse.IdAndVpToken {
        val a = requiredIdToken()
        val b = requiredVpToken()
        return WalletResponse.IdAndVpToken(a.idToken, b.vpToken, b.presentationSubmission)
    }

    val maybeError: WalletResponse.Error? = error?.let { WalletResponse.Error(it, errorDescription) }

    return maybeError ?: when (presentation.type) {
        is PresentationType.IdTokenRequest -> WalletResponse.IdToken(requiredIdToken().idToken)
        is PresentationType.VpTokenRequest -> WalletResponse.VpToken(
            requiredVpToken().vpToken,
            requiredVpToken().presentationSubmission,
        )

        is PresentationType.IdAndVpToken -> WalletResponse.IdAndVpToken(
            requiredIdAndVpToken().idToken,
            requiredIdAndVpToken().vpToken,
            requiredIdAndVpToken().presentationSubmission,
        )
    }
}

@Serializable
data class WalletResponseAcceptedTO(
    @SerialName("redirect_uri") val redirectUri: String,
)

/**
 * This is use case 12 of the [Presentation] process.
 *
 * The caller (wallet) may POST the [AuthorisationResponseTO] to the verifier back-end
 */
fun interface PostWalletResponse {

    context(Raise<WalletResponseValidationError>)
    suspend operator fun invoke(walletResponse: AuthorisationResponse): Option<WalletResponseAcceptedTO>
}

class PostWalletResponseLive(
    private val loadPresentationByRequestId: LoadPresentationByRequestId,
    private val storePresentation: StorePresentation,
    private val verifyJarmJwtSignature: VerifyJarmJwtSignature,
    private val clock: Clock,
    private val verifierConfig: VerifierConfig,
    private val generateResponseCode: GenerateResponseCode,
    private val createQueryWalletResponseRedirectUri: CreateQueryWalletResponseRedirectUri,
) : PostWalletResponse {

    context(Raise<WalletResponseValidationError>)
    override suspend operator fun invoke(walletResponse: AuthorisationResponse): Option<WalletResponseAcceptedTO> {
        val presentation = loadPresentation(walletResponse)

        // Verify the AuthorisationResponse matches what is expected for the Presentation
        val responseMode = walletResponse.responseMode()
        ensure(presentation.responseMode == responseMode) {
            WalletResponseValidationError.UnexpectedResponseMode(
                presentation.requestId,
                expected = presentation.responseMode,
                actual = responseMode,
            )
        }

        // generate response depending on response method (DirectPost or DirectPostJwt)
        val responseObject = responseObject(walletResponse, presentation)

        // TODO: Find out which format is used (if `...+zkp` or not) using:
        // responseObject.presentationSubmission!!.descriptorMaps

        // TODO: Verify signature
        //   i.e. if format is e.g. `vc+sd-jwt+zkp`, call `ZKPVerifier(...).verifyChallenge(transactionId, VpTokenFormat.SDJWT, responseObject.vpToken`
        //   (ZKPVerifier should be initialized centrally having the issuer public key hardcoded for now)

        // for this use case (let frontend display the submitted data) we store the wallet response
        // Put wallet response into presentation object and store into db
        val submitted = submit(presentation, responseObject).also { storePresentation(it) }

        return when (val getWalletResponseMethod = presentation.getWalletResponseMethod) {
            is GetWalletResponseMethod.Redirect ->
                with(createQueryWalletResponseRedirectUri) {
                    requireNotNull(submitted.responseCode) { "ResponseCode expected in Submitted state but not found" }
                    val redirectUri = getWalletResponseMethod.redirectUri(submitted.responseCode)
                    WalletResponseAcceptedTO(redirectUri.toExternalForm()).some()
                }

            GetWalletResponseMethod.Poll -> None
        }
    }

    context(Raise<WalletResponseValidationError>)
    private suspend fun loadPresentation(walletResponse: AuthorisationResponse): RequestObjectRetrieved {
        val state = when (walletResponse) {
            is AuthorisationResponse.DirectPost -> walletResponse.response.state
            is AuthorisationResponse.DirectPostJwt -> walletResponse.state
        }
        ensureNotNull(state) { WalletResponseValidationError.MissingState }
        val requestId = RequestId(state)

        val presentation = loadPresentationByRequestId(requestId)
        ensureNotNull(presentation) { WalletResponseValidationError.PresentationDefinitionNotFound(requestId) }
        ensure(presentation is RequestObjectRetrieved) {
            WalletResponseValidationError.PresentationNotInExpectedState(
                requestId,
            )
        }
        return presentation
    }

    context(Raise<WalletResponseValidationError>)
    private fun responseObject(
        walletResponse: AuthorisationResponse,
        presentation: RequestObjectRetrieved,
    ): AuthorisationResponseTO = when (walletResponse) {
        is AuthorisationResponse.DirectPost -> walletResponse.response
        is AuthorisationResponse.DirectPostJwt -> {
            val response = verifyJarmJwtSignature(
                jarmOption = verifierConfig.clientMetaData.jarmOption,
                ephemeralEcPrivateKey = presentation.ephemeralEcPrivateKey,
                jarmJwt = walletResponse.jarm,
            ).getOrThrow()
            ensure(response.state == walletResponse.state) { WalletResponseValidationError.IncorrectStateInJarm }
            response
        }
    }

    context(Raise<WalletResponseValidationError>)
    private suspend fun submit(
        presentation: RequestObjectRetrieved,
        responseObject: AuthorisationResponseTO,
    ): Presentation.Submitted {
        // add the wallet response to the presentation
        val walletResponse = responseObject.toDomain(presentation)
        val responseCode = when (presentation.getWalletResponseMethod) {
            GetWalletResponseMethod.Poll -> null
            is GetWalletResponseMethod.Redirect -> generateResponseCode()
        }
        return presentation.submit(clock, walletResponse, responseCode).getOrThrow()
    }
}

/**
 * Gets the [ResponseModeOption] that corresponds to the receiver [AuthorisationResponse].
 */
private fun AuthorisationResponse.responseMode(): ResponseModeOption = when (this) {
    is AuthorisationResponse.DirectPost -> ResponseModeOption.DirectPost
    is AuthorisationResponse.DirectPostJwt -> ResponseModeOption.DirectPostJwt
}
