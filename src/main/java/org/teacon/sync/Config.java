/*
 * Copyright (C) 2021 3TUSK
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

// SPDX-Identifier: LGPL-2.1-or-later

package org.teacon.sync;

import java.net.URL;
import java.util.Collections;
import java.util.List;

public final class Config {

    /**
     * URL that points to the mod list to download. Supported protocols include
     * http, https, files (i.e. local file) and others.
     *
     * @see URL
     * @see URL#getProtocol()
     */
    public List<TypeEntry> syncFiles = Collections.emptyList();
    /**
     * Local mod list location for downloading
     */
    public String sigDir = "sig";
    public String baseDir = ".remoteSync";
    public String keyRingPath = "pub_key.asc";
    /**
     * List of URLs of key servers. These key servers SHOULD support the HTTP
     * Keyserver Protocol.
     */
    public List<URL> keyServers = Collections.emptyList();
    /**
     * List of public keys identified by key IDs. 32-bit key ID, 64-bit key ID,
     * version 3 fingerprint and version 4 fingerprint are acceptable; these key
     * IDs SHOULD be prefixed with {@code 0x} as a mark of hexadecimal number.
     */
    public List<String> keyIds = Collections.emptyList();
    /**
     * Amount of time to wait before giving up a connection, measured in milliseconds.
     */
    public int timeout = 15000;
    /**
     * If true, RemoteSync will always try using local cache, even if the file with the
     * same name on the remote server has been updated.
     * <p>
     * Note that this config option will not take effect when fetching the mod list. The
     * mod list is expected to be always the latest.
     * </p>
     */
    public boolean preferLocalCache = true;
    public long fullCheckTs = 0;
}
