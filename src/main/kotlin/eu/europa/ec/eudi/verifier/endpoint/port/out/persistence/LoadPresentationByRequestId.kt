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
package eu.europa.ec.eudi.verifier.endpoint.port.out.persistence

import eu.europa.ec.eudi.verifier.endpoint.domain.Presentation
import eu.europa.ec.eudi.verifier.endpoint.domain.RequestId

/**
 * Loads a [Presentation] from a storage
 */
fun interface LoadPresentationByRequestId {
    suspend operator fun invoke(requestId: RequestId): Presentation?
}