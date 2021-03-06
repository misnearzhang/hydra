/*
 * Copyright (C) 2016 Pluralsight, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package hydra.ingest.http

import akka.actor._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.github.vonnagy.service.container.http.routing.RoutedEndpoints
import configs.syntax._
import hydra.common.config.ConfigSupport
import hydra.common.logging.LoggingAdapter
import hydra.core.http.HydraDirectives
import hydra.ingest.bootstrap.HydraIngestorRegistryClient
import hydra.ingest.services.IngestorRegistry.{ FindAll, LookupResult }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ FiniteDuration, _ }

/**
 * Created by alexsilva on 12/22/15.
 */
class IngestorRegistryEndpoint(implicit val system: ActorSystem, implicit val e: ExecutionContext)
  extends RoutedEndpoints with LoggingAdapter with HydraIngestJsonSupport with HydraDirectives with ConfigSupport {

  private val registryLookupTimeout = applicationConfig
    .get[FiniteDuration]("ingest.service-lookup.timeout").valueOrElse(5.seconds)

  lazy val registry = HydraIngestorRegistryClient(applicationConfig).registry

  private implicit val timeout = Timeout(registryLookupTimeout)

  override val route: Route =
    path("ingestors" ~ Slash.?) {
      get {
        onSuccess(registry ? FindAll) {
          case response: LookupResult => complete(response.ingestors)
        }
      }
    }
}
