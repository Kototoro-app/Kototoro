package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.MainAPI

/** Cloudstream's wrapper around a provider; plugins reading the home screen's model only ask it for the provider. */
class APIRepository(val api: MainAPI)
