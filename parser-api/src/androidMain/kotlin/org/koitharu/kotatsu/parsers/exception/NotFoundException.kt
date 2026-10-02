package org.koitharu.kotatsu.parsers.exception

import org.jsoup.HttpStatusException
import java.net.HttpURLConnection

public class NotFoundException(
	message: String,
	url: String,
) : HttpStatusException(message, HttpURLConnection.HTTP_NOT_FOUND, url)
