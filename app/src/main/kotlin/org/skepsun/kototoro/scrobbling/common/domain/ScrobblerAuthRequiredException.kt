package org.skepsun.kototoro.scrobbling.common.domain

import okio.IOException
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

class ScrobblerAuthRequiredException(
    val scrobbler: ScrobblerService,
) : IOException()
