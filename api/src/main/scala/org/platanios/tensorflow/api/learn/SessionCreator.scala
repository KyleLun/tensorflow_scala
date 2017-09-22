/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.tensorflow.api.learn

import org.platanios.tensorflow.api.config.SessionConfig
import org.platanios.tensorflow.api.core.client.Session
import org.platanios.tensorflow.api.learn.hooks.Hook
import org.platanios.tensorflow.api.ops.Op

import java.nio.file.Path

/** Factory for [[Session]]s.
  *
  * @author Emmanouil Antonios Platanios
  */
trait SessionCreator {
  /** Creates a new [[Session]]. */
  def createSession(): Session
}

/** Session factory for `CHIEF`s.
  *
  * @param  master          TensorFlow master to use.
  * @param  sessionScaffold Session scaffold used for gathering and/or building supportive ops. If not specified, a
  *                         default one is created. The session scaffold is used to finalize the graph.
  * @param  sessionConfig   Session configuration to be used for the new sessions.
  * @param  checkpointPath  Path to either a checkpoint file to restore the model from, or a directory containing
  *                         multiple checkpoint files, in which case the latest checkpoint in the directory will be
  *                         used.
  *
  * @author Emmanouil Antonios Platanios
  */
case class ChiefSessionCreator(
    master: String = "",
    sessionScaffold: SessionScaffold = SessionScaffold(),
    sessionConfig: Option[SessionConfig] = None,
    checkpointPath: Option[Path] = None
) extends SessionCreator {
  private[this] var builtSessionScaffold: BuiltSessionScaffold = _
  private[this] var sessionManager      : SessionManager       = _

  override def createSession(): Session = {
    if (builtSessionScaffold == null)
      builtSessionScaffold = sessionScaffold.build()
    if (sessionManager == null)
      sessionManager = SessionManager(
        graph = Op.currentGraph,
        readyOp = Option(builtSessionScaffold.readyOp),
        readyForLocalInitOp = Option(builtSessionScaffold.readyForLocalInitOp),
        localInitOp = Option(builtSessionScaffold.localInitOp))
    sessionManager.prepareSession(
      master = master,
      saver = builtSessionScaffold.saver,
      checkpointPath = checkpointPath,
      sessionConfig = sessionConfig,
      initOp = Option(builtSessionScaffold.initOp),
      initFeedMap = builtSessionScaffold.initFeedMap,
      initFunction = builtSessionScaffold.internalInitFunction
    )
  }
}

/** Session factory for `WORKER`s.
  *
  * @param  master          TensorFlow master to use.
  * @param  sessionScaffold Session scaffold used for gathering and/or building supportive ops. If not specified, a
  *                         default one is created. The session scaffold is used to finalize the graph.
  * @param  sessionConfig   Session configuration to be used for the new sessions.
  *
  * @author Emmanouil Antonios Platanios
  */
case class WorkerSessionCreator(
    master: String = "",
    sessionScaffold: SessionScaffold = SessionScaffold(),
    sessionConfig: Option[SessionConfig] = None
) extends SessionCreator {
  private[this] var builtSessionScaffold: BuiltSessionScaffold = _
  private[this] var sessionManager      : SessionManager       = _

  override def createSession(): Session = {
    if (builtSessionScaffold == null)
      builtSessionScaffold = sessionScaffold.build()
    if (sessionManager == null)
      sessionManager = SessionManager(
        graph = Op.currentGraph,
        readyOp = Option(builtSessionScaffold.readyOp),
        readyForLocalInitOp = Option(builtSessionScaffold.readyForLocalInitOp),
        localInitOp = Option(builtSessionScaffold.localInitOp))
    sessionManager.waitForSession(
      master = master,
      sessionConfig = sessionConfig,
      maxWaitSeconds = 30 * 60 // Wait up to 30 minutes for the session to become ready.
    )
  }
}

/** Session creator that uses a coordinator for session management/recovery across potentially multiple threads.
  *
  * @param  sessionCreator         Wrapped session creator.
  * @param  hooks                  Hooks to use.
  * @param  stopGracePeriodSeconds Number of seconds given to threads to stop after a stop has been requested.
  *
  * @author Emmanouil Antonios Platanios
  */
private[learn] case class CoordinatedSessionCreator private[learn](
    sessionCreator: SessionCreator,
    hooks: Seq[Hook],
    stopGracePeriodSeconds: Int
) extends SessionCreator {
  private[learn] var session    : Option[Session]     = None
  private[learn] var coordinator: Option[Coordinator] = None

  override def createSession(): Session = {
    session = Some(sessionCreator.createSession())
    coordinator = Some(Coordinator())
    // TODO: !!! [QUEUE_RUNNERS] Start all queue runners.
    // Inform the hooks that a new session has been created.
    hooks.foreach(h => h.afterSessionCreation(session.get, coordinator.get))
    CoordinatedSession(HookedSession(session.get, hooks), coordinator.get, stopGracePeriodSeconds)
  }
}
