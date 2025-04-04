/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.core.connection

/**
 * A general ConnectionException is thrown whenever the reason, why a
 * [org.openhab.habdroid.core.connection.Connection] was not able to be obtained, is known.
 * Otherwise one of the subclass exceptions may be thrown.
 */
open class ConnectionException : Exception()

class ConnectionNotInitializedException : ConnectionException()

class NetworkNotAvailableException : ConnectionException()

class NoUrlInformationException(private val local: Boolean) : ConnectionException() {
    fun wouldHaveUsedLocalConnection() = local
}

class WrongWifiException : ConnectionException()

class NotACloudServerException : Exception()
