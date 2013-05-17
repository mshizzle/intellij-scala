package org.jetbrains.sbt
package project.settings

import com.intellij.util.messages.Topic

/**
 * @author Pavel Fatin
 */
object SbtTopic extends Topic[SbtSettingsListener]("SBT-cpecific settings", classOf[SbtSettingsListener])