/*******************************************************************************
 * Copyright (c) 2010, 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.internal.server.servlets.workspace;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.orion.internal.server.servlets.Activator;
import org.eclipse.orion.internal.server.servlets.ProtocolConstants;
import org.eclipse.orion.server.core.LogHelper;
import org.eclipse.orion.server.core.resources.Base64;
import org.eclipse.orion.server.core.resources.Base64Counter;
import org.osgi.service.prefs.BackingStoreException;

/**
 * An Eclipse web project.
 */
public class WebProject extends WebElement {
	public static final String PROJECT_NODE_NAME = "Projects"; //$NON-NLS-1$
	private static final Base64Counter projectCounter = new Base64Counter();

	/**
	 * Creates a workspace instance with the given globally unique id. The workspace
	 * may or may not actually exist yet in the backing storage.
	 * @param id the globally unique workspace id
	 * @return A workspace instance with the given id
	 */
	public static WebProject fromId(String id) {
		WebProject result = new WebProject((IEclipsePreferences) scope.getNode(PROJECT_NODE_NAME).node(id));
		result.setId(id);
		return result;
	}

	/**
	 * Returns the next available project id. The id is guaranteed to be globally unique within
	 * this server.
	 * @return the next available project id, or <code>null</code> if an id could not be allocated
	 */
	public static String nextProjectId() {
		synchronized (projectCounter) {
			String candidate;
			do {
				candidate = projectCounter.toString();
				projectCounter.increment();
			} while (exists(candidate) || (!caseSensitive && containsUpperCase(candidate)));
			return candidate;
		}
	}

	/**
	 * Returns whether a project  with the given id already exists.
	 * @param id The id of the project
	 * @return <code>true</code> if the project already exists, and <code>false</code> otherwise.
	 */
	public static boolean exists(String id) {
		try {
			return scope.getNode(PROJECT_NODE_NAME).nodeExists(id);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Returns a list of all known web projects.
	 */
	public static List<WebProject> allProjects() {
		List<WebProject> result = new ArrayList<WebProject>();
		IEclipsePreferences projectRoot = scope.getNode(PROJECT_NODE_NAME);
		try {
			String[] ids = projectRoot.childrenNames();
			for (String id : ids)
				result.add(WebProject.fromId(id));
		} catch (BackingStoreException e) {
			LogHelper.log(e);
		}
		return result;
	}

	public WebProject(IEclipsePreferences store) {
		super(store);
	}

	public void remove() {
		store.remove(ProtocolConstants.KEY_CONTENT_LOCATION);
		store.remove(ProtocolConstants.KEY_ID);
		store.remove(ProtocolConstants.KEY_NAME);
	}

	/**
	 * Sets the location of the contents of this project. The location is either
	 * relative to the workspace servlet location, or an absolute URI in the 
	 * case where content is stored on a different server.
	 */
	public void setContentLocation(URI contentURI) {
		String uriString = null;
		if (contentURI.getUserInfo() == null) {
			uriString = contentURI.toString();
		} else {
			//strip out credential information
			try {
				URI noCredentials = new URI(contentURI.getScheme(), null, contentURI.getHost(), contentURI.getPort(), contentURI.getPath(), contentURI.getQuery(), contentURI.getFragment());
				uriString = noCredentials.toString();
			} catch (URISyntaxException e) {
				//should never happen because we are stripping info from a valid URI
				throw new RuntimeException(e);
			}
		}
		store.put(ProtocolConstants.KEY_CONTENT_LOCATION, uriString);
	}

	/**
	 * Returns the location of the contents of this project. The result is either
	 * relative to the workspace servlet location, or an absolute URI in the 
	 * case where content is stored on a different server.
	 * <p>
	 * This method never returns null. Project location will default to a relative
	 * URI containing only the project id.
	 * </p>
	 * @return The location of the contents of this project
	 */
	public URI getContentLocation() {
		String result = store.get(ProtocolConstants.KEY_CONTENT_LOCATION, null);
		if (result != null) {
			try {
				return new URI(result);
			} catch (URISyntaxException e) {
				//fall through below
			}
		}
		//by default the location is simply the unique id of the project.
		return URI.create(getId());
	}

	/**
	 * Returns the server local file system location for this project's contents.
	 * @param request The current servlet request, or <code>null</code> if not
	 * called from within a servlet request.
	 * @throws CoreException 
	 */
	public IFileStore getProjectStore(HttpServletRequest request) throws CoreException {
		URI location = getContentLocation();
		if (location.isAbsolute()) {
			//insert authentication details from request if available
			if (request != null && !EFS.SCHEME_FILE.equals(location.getScheme()) && location.getUserInfo() == null) {
				String authHead = request.getHeader("Authorization"); //$NON-NLS-1$
				if (authHead != null && authHead.toUpperCase(Locale.ENGLISH).startsWith("BASIC")) { //$NON-NLS-1$
					String base64 = authHead.substring(6);
					String authString = new String(Base64.decode(base64.getBytes()));
					if (authString.length() > 0) {
						try {
							location = new URI(location.getScheme(), authString, location.getHost(), location.getPort(), location.getPath(), location.getQuery(), location.getFragment());
						} catch (URISyntaxException e) {
							//just fall through and use original location
						}
					}
				}
			}
			return EFS.getStore(location);
		}
		//there is no scheme but it could still be an absolute path
		IPath localPath = new Path(location.getPath());
		if (localPath.isAbsolute()) {
			return EFS.getLocalFileSystem().getStore(localPath);
		}
		//treat relative location as relative to the file system root
		URI rootLocation = Activator.getDefault().getRootLocationURI();
		IFileStore root = EFS.getStore(rootLocation);
		return root.getChild(location.toString());
	}

	/**
	 * Returns the server local file system location for this project's contents.
	 * @throws CoreException 
	 */
	public IFileStore getProjectStore() throws CoreException {
		return getProjectStore(null);
	}

}
