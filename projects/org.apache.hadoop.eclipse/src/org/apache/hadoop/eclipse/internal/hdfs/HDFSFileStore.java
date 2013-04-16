/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.eclipse.internal.hdfs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.eclipse.Activator;
import org.apache.hadoop.eclipse.hdfs.HDFSClient;
import org.apache.hadoop.eclipse.hdfs.ResourceInformation;
import org.apache.hadoop.eclipse.internal.model.HDFSServer;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.filesystem.provider.FileInfo;
import org.eclipse.core.filesystem.provider.FileStore;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.URIUtil;

/**
 * Represents a file or folder in the Hadoop Distributed File System. This
 * {@link IFileStore} knows about the remote HDFS resource, and the local
 * resource. Based on this, it is able to tell a lot about each file and its
 * sync status.
 * 
 * @author Srimanth Gunturi
 */
public class HDFSFileStore extends FileStore {

	private static final Logger logger = Logger.getLogger(HDFSFileStore.class);
	private final HDFSURI uri;
	private File localFile = null;
	private static HDFSManager manager = HDFSManager.INSTANCE;
	private IFileInfo serverFileInfo = null;
	private HDFSServer hdfsServer;

	public HDFSFileStore(HDFSURI uri) {
		this.uri = uri;
	}

	protected HDFSServer getServer() {
		if (hdfsServer == null) {
			hdfsServer = HDFSManager.INSTANCE.getServer(this.uri.getURI().toString());
		}
		return hdfsServer;
	}

	@Override
	public String[] childNames(int options, IProgressMonitor monitor) throws CoreException {
		List<String> childNamesList = new ArrayList<String>();
		if (getServer() != null) {
			try {
				List<ResourceInformation> listResources = getClient().listResources(uri.getURI());
				for (ResourceInformation lr : listResources) {
					if (lr != null)
						childNamesList.add(lr.getName());
				}
			} catch (IOException e) {
				throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
			}
		}
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: childNames():"+childNamesList);
		return childNamesList.toArray(new String[childNamesList.size()]);
	}

	@Override
	public IFileInfo fetchInfo(int options, IProgressMonitor monitor) throws CoreException {
		manager.startServerOperation(uri.getURI().toString());
		if (serverFileInfo == null) {
			FileInfo fi = new FileInfo(getName());
			if (getServer() != null) {
				try {
					if (".project".equals(getName())) {
						fi.setExists(getLocalFile().exists());
						fi.setLength(getLocalFile().length());
					} else {
						ResourceInformation fileInformation = getClient().getResourceInformation(uri.getURI());
						if (fileInformation != null) {
							fi.setDirectory(fileInformation.isFolder());
							fi.setExists(true);
							fi.setLastModified(fileInformation.getLastModifiedTime());
							fi.setLength(fileInformation.getSize());
							fi.setName(fileInformation.getName());
						}
					}
				} catch (IOException e) {
					throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
				} finally {
					manager.stopServerOperation(uri.getURI().toString());
				}
			} else {
				// No server definition
				fi.setExists(false);
			}
			serverFileInfo = fi;
		}
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: fetchInfo(): "+HDFSUtilites.getDebugMessage(serverFileInfo));
		return serverFileInfo;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.core.filesystem.provider.FileStore#putInfo(org.eclipse.core.filesystem.IFileInfo, int, org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public void putInfo(IFileInfo info, int options, IProgressMonitor monitor) throws CoreException {
		try {
			ResourceInformation ri = new ResourceInformation();
			ri.setFolder(info.isDirectory());
			ri.setLastModifiedTime(info.getLastModified());
			getClient().setResourceInformation(uri.getURI(), ri);
		} catch (IOException e) {
			throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
		}
	}
	
	/**
	 * When this file store makes changes which obsolete the server information,
	 * it should clear the server information.
	 */
	protected void clearServerFileInfo() {
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: clearServerFileInfo()");
		this.serverFileInfo = null;
	}

	@Override
	public IFileStore getChild(String name) {
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: getChild():"+name);
		return new HDFSFileStore(uri.append(name));
	}

	@Override
	public String getName() {
		String lastSegment = uri.lastSegment();
		if (lastSegment == null)
			lastSegment = "/";
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: getName():"+lastSegment);
		return lastSegment;
	}

	@Override
	public IFileStore getParent() {
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: getParent()");
		try {
			return new HDFSFileStore(uri.removeLastSegment());
		} catch (URISyntaxException e) {
			logger.log(Level.WARN, e.getMessage(), e);
		}
		return null;
	}

	@Override
	public InputStream openInputStream(int options, IProgressMonitor monitor) throws CoreException {
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: openInputStream()");
		if (".project".equals(getName())) {
			try {
				final File localFile = getLocalFile();
				if(!localFile.exists())
					localFile.createNewFile();
				return new FileInputStream(localFile);
			} catch (FileNotFoundException e) {
				throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
			} catch (IOException e) {
				throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
			}
		} else {
			try {
				return getClient().openInputStream(uri.getURI(), monitor);
			} catch (IOException e) {
				throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
			}
		}
	}

	@Override
	public URI toURI() {
		return uri.getURI();
	}

	protected HDFSClient getClient() throws CoreException {
		IConfigurationElement[] elementsFor = Platform.getExtensionRegistry().getConfigurationElementsFor("org.apache.hadoop.eclipse.hdfsclient");
		try {
			return (HDFSClient) elementsFor[0].createExecutableExtension("class");
		} catch (CoreException t) {
			throw t;
		}
	}

	/**
	 * @return the localFile
	 * @throws CoreException 
	 */
	public File getLocalFile() throws CoreException {
		if (localFile == null) {
			final HDFSManager hdfsManager = HDFSManager.INSTANCE;
			final String uriString = uri.getURI().toString();
			HDFSServer server = hdfsManager.getServer(uriString);
			if (server != null) {
				File workspaceFolder = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile();
				try {
					URI relativeURI = URIUtil.makeRelative(uri.getURI(), new URI(server.getUri()));
					String relativePath = hdfsManager.getProjectName(server) + "/" + relativeURI.toString();
					localFile = new File(workspaceFolder, relativePath);
				} catch (URISyntaxException e) {
					throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
				}
			} else
				logger.error("No server associated with uri: " + uriString);
		}
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: getLocalFile():"+localFile);
		return localFile;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.core.filesystem.provider.FileStore#mkdir(int,
	 * org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public IFileStore mkdir(int options, IProgressMonitor monitor) throws CoreException {
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: mkdir()");
		try {
			clearServerFileInfo();
			if (getClient().mkdirs(uri.getURI(), monitor)) {
				return this;
			} else {
				return null;
			}
		} catch (IOException e) {
			logger.error("Unable to mkdir: " + uri);
			throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
		}
	}

	public boolean isLocalFile() {
		try {
			File localFile = getLocalFile();
			return localFile != null && localFile.exists();
		} catch (CoreException e) {
			logger.debug("Unable to determine if file is local", e);
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.core.filesystem.provider.FileStore#openOutputStream(int,
	 * org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public OutputStream openOutputStream(int options, IProgressMonitor monitor) throws CoreException {
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: openOutputStream()");
		if (".project".equals(getName())) {
			try {
				File dotProjectFile = getLocalFile();
				if(!dotProjectFile.exists()){
					dotProjectFile.getParentFile().mkdirs();
					dotProjectFile.createNewFile();
				}
				return new FileOutputStream(dotProjectFile);
			} catch (FileNotFoundException e) {
				throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
			} catch (IOException e) {
				throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
			}
		}else{
			try {
				if (fetchInfo().exists()) {
					clearServerFileInfo();
					return getClient().openOutputStream(uri.getURI(), monitor);
				} else {
					clearServerFileInfo();
					return getClient().createOutputStream(uri.getURI(), monitor);
				}
			} catch (IOException e) {
				throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.core.filesystem.provider.FileStore#delete(int,
	 * org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public void delete(int options, IProgressMonitor monitor) throws CoreException {
		if(logger.isDebugEnabled())
			logger.debug("["+uri+"]: delete()");
		try {
			final HDFSServer server = getServer();
			if (server != null) {
				if(server.getUri().equals(uri.getURI().toString())){
					// Server location is the same as the project - so we just
					// disconnect instead of actually deleting the root folder
					// on HDFS.
				}else{
					clearServerFileInfo();
					getClient().delete(uri.getURI(), monitor);
				}
			} else {
				// Not associated with any server, we just disconnect.
			}
		} catch (IOException e) {
			logger.error("Unable to delete: " + uri);
			throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID, e.getMessage(), e));
		}
	}
}
