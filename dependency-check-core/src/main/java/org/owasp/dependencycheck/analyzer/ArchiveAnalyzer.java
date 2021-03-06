/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2013 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.analyzer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipUtils;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.analyzer.exception.ArchiveExtractionException;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.utils.FileFilterBuilder;
import org.owasp.dependencycheck.utils.FileUtils;
import org.owasp.dependencycheck.utils.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * An analyzer that extracts files from archives and ensures any supported files contained within the archive are added to the
 * dependency list.</p>
 *
 * @author Jeremy Long
 */
public class ArchiveAnalyzer extends AbstractFileTypeAnalyzer {

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveAnalyzer.class);
    /**
     * The buffer size to use when extracting files from the archive.
     */
    private static final int BUFFER_SIZE = 4096;
    /**
     * The count of directories created during analysis. This is used for creating temporary directories.
     */
    private static int dirCount = 0;
    /**
     * The parent directory for the individual directories per archive.
     */
    private File tempFileLocation = null;
    /**
     * The max scan depth that the analyzer will recursively extract nested archives.
     */
    private static final int MAX_SCAN_DEPTH = Settings.getInt("archive.scan.depth", 3);
    /**
     * Tracks the current scan/extraction depth for nested archives.
     */
    private int scanDepth = 0;

    //<editor-fold defaultstate="collapsed" desc="All standard implementation details of Analyzer">
    /**
     * The name of the analyzer.
     */
    private static final String ANALYZER_NAME = "Archive Analyzer";
    /**
     * The phase that this analyzer is intended to run in.
     */
    private static final AnalysisPhase ANALYSIS_PHASE = AnalysisPhase.INITIAL;
    /**
     * The set of things we can handle with Zip methods
     */
    private static final Set<String> ZIPPABLES = newHashSet("zip", "ear", "war", "jar", "sar", "apk", "nupkg");
    /**
     * The set of file extensions supported by this analyzer. Note for developers, any additions to this list will need to be
     * explicitly handled in extractFiles().
     */
    private static final Set<String> EXTENSIONS = newHashSet("tar", "gz", "tgz");

    /**
     * The set of file extensions to remove from the engine's collection of dependencies.
     */
    private static final Set<String> REMOVE_FROM_ANALYSIS = newHashSet("zip", "tar", "gz", "tgz"); //TODO add nupkg, apk, sar?

    static {
        final String additionalZipExt = Settings.getString(Settings.KEYS.ADDITIONAL_ZIP_EXTENSIONS);
        if (additionalZipExt != null) {
            final Set<String> ext = new HashSet<String>(Arrays.asList(additionalZipExt));
            ZIPPABLES.addAll(ext);
        }
        EXTENSIONS.addAll(ZIPPABLES);
    }

    private static final FileFilter FILTER = FileFilterBuilder.newInstance().addExtensions(EXTENSIONS).build();

    @Override
    protected FileFilter getFileFilter() {
        return FILTER;
    }

    /**
     * Returns the name of the analyzer.
     *
     * @return the name of the analyzer.
     */
    @Override
    public String getName() {
        return ANALYZER_NAME;
    }

    /**
     * Returns the phase that the analyzer is intended to run in.
     *
     * @return the phase that the analyzer is intended to run in.
     */
    @Override
    public AnalysisPhase getAnalysisPhase() {
        return ANALYSIS_PHASE;
    }
    //</editor-fold>

    /**
     * Returns the key used in the properties file to reference the analyzer's enabled property.
     *
     * @return the analyzer's enabled property setting key
     */
    @Override
    protected String getAnalyzerEnabledSettingKey() {
        return Settings.KEYS.ANALYZER_ARCHIVE_ENABLED;
    }

    /**
     * The initialize method does nothing for this Analyzer.
     *
     * @throws Exception is thrown if there is an exception deleting or creating temporary files
     */
    @Override
    public void initializeFileTypeAnalyzer() throws Exception {
        final File baseDir = Settings.getTempDirectory();
        tempFileLocation = File.createTempFile("check", "tmp", baseDir);
        if (!tempFileLocation.delete()) {
            final String msg = String.format("Unable to delete temporary file '%s'.", tempFileLocation.getAbsolutePath());
            throw new AnalysisException(msg);
        }
        if (!tempFileLocation.mkdirs()) {
            final String msg = String.format("Unable to create directory '%s'.", tempFileLocation.getAbsolutePath());
            throw new AnalysisException(msg);
        }
    }

    /**
     * The close method deletes any temporary files and directories created during analysis.
     *
     * @throws Exception thrown if there is an exception deleting temporary files
     */
    @Override
    public void close() throws Exception {
        if (tempFileLocation != null && tempFileLocation.exists()) {
            LOGGER.debug("Attempting to delete temporary files");
            final boolean success = FileUtils.delete(tempFileLocation);
            if (!success && tempFileLocation != null && tempFileLocation.exists() && tempFileLocation.list().length > 0) {
                LOGGER.warn("Failed to delete some temporary files, see the log for more details");
            }
        }
    }

    /**
     * Analyzes a given dependency. If the dependency is an archive, such as a WAR or EAR, the contents are extracted, scanned,
     * and added to the list of dependencies within the engine.
     *
     * @param dependency the dependency to analyze
     * @param engine the engine scanning
     * @throws AnalysisException thrown if there is an analysis exception
     */
    @Override
    public void analyzeFileType(Dependency dependency, Engine engine) throws AnalysisException {
        final File f = new File(dependency.getActualFilePath());
        final File tmpDir = getNextTempDirectory();
        extractFiles(f, tmpDir, engine);

        //make a copy
        List<Dependency> dependencies = new ArrayList<Dependency>(engine.getDependencies());
        engine.scan(tmpDir);
        List<Dependency> newDependencies = engine.getDependencies();
        if (dependencies.size() != newDependencies.size()) {
            //get the new dependencies
            final Set<Dependency> dependencySet = new HashSet<Dependency>();
            dependencySet.addAll(newDependencies);
            dependencySet.removeAll(dependencies);

            for (Dependency d : dependencySet) {
                //fix the dependency's display name and path
                final String displayPath = String.format("%s%s",
                        dependency.getFilePath(),
                        d.getActualFilePath().substring(tmpDir.getAbsolutePath().length()));
                final String displayName = String.format("%s: %s",
                        dependency.getFileName(),
                        d.getFileName());
                d.setFilePath(displayPath);
                d.setFileName(displayName);

                //TODO - can we get more evidence from the parent? EAR contains module name, etc.
                //analyze the dependency (i.e. extract files) if it is a supported type.
                if (this.accept(d.getActualFile()) && scanDepth < MAX_SCAN_DEPTH) {
                    scanDepth += 1;
                    analyze(d, engine);
                    scanDepth -= 1;
                }
            }
        }
        if (REMOVE_FROM_ANALYSIS.contains(dependency.getFileExtension())) {
            if ("zip".equals(dependency.getFileExtension()) && isZipFileActuallyJarFile(dependency)) {
                final File tdir = getNextTempDirectory();
                final String fileName = dependency.getFileName();

                LOGGER.info(String.format("The zip file '%s' appears to be a JAR file, making a copy and analyzing it as a JAR.", fileName));

                final File tmpLoc = new File(tdir, fileName.substring(0, fileName.length() - 3) + "jar");
                try {
                    org.apache.commons.io.FileUtils.copyFile(tdir, tmpLoc);
                    dependencies = new ArrayList<Dependency>(engine.getDependencies());
                    engine.scan(tmpLoc);
                    newDependencies = engine.getDependencies();
                    if (dependencies.size() != newDependencies.size()) {
                        //get the new dependencies
                        final Set<Dependency> dependencySet = new HashSet<Dependency>();
                        dependencySet.addAll(newDependencies);
                        dependencySet.removeAll(dependencies);
                        if (dependencySet.size() != 1) {
                            LOGGER.info("Deep copy of ZIP to JAR file resulted in more then one dependency?");
                        }
                        for (Dependency d : dependencySet) {
                            //fix the dependency's display name and path
                            d.setFilePath(dependency.getFilePath());
                            d.setDisplayFileName(dependency.getFileName());
                        }
                    }
                } catch (IOException ex) {
                    LOGGER.debug("Unable to perform deep copy on '{}'", dependency.getActualFile().getPath(), ex);
                }
            }
            engine.getDependencies().remove(dependency);
        }
        Collections.sort(engine.getDependencies());
    }

    /**
     * Retrieves the next temporary directory to extract an archive too.
     *
     * @return a directory
     * @throws AnalysisException thrown if unable to create temporary directory
     */
    private File getNextTempDirectory() throws AnalysisException {
        dirCount += 1;
        final File directory = new File(tempFileLocation, String.valueOf(dirCount));
        //getting an exception for some directories not being able to be created; might be because the directory already exists?
        if (directory.exists()) {
            return getNextTempDirectory();
        }
        if (!directory.mkdirs()) {
            final String msg = String.format("Unable to create temp directory '%s'.", directory.getAbsolutePath());
            throw new AnalysisException(msg);
        }
        return directory;
    }

    /**
     * Extracts the contents of an archive into the specified directory.
     *
     * @param archive an archive file such as a WAR or EAR
     * @param destination a directory to extract the contents to
     * @param engine the scanning engine
     * @throws AnalysisException thrown if the archive is not found
     */
    private void extractFiles(File archive, File destination, Engine engine) throws AnalysisException {
        if (archive == null || destination == null) {
            return;
        }

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(archive);
        } catch (FileNotFoundException ex) {
            LOGGER.debug("", ex);
            throw new AnalysisException("Archive file was not found.", ex);
        }
        final String archiveExt = FileUtils.getFileExtension(archive.getName()).toLowerCase();
        try {
            if (ZIPPABLES.contains(archiveExt)) {
                extractArchive(new ZipArchiveInputStream(new BufferedInputStream(fis)), destination, engine);
            } else if ("tar".equals(archiveExt)) {
                extractArchive(new TarArchiveInputStream(new BufferedInputStream(fis)), destination, engine);
            } else if ("gz".equals(archiveExt) || "tgz".equals(archiveExt)) {
                final String uncompressedName = GzipUtils.getUncompressedFilename(archive.getName());
                File f = new File(destination, uncompressedName);
                if (engine.accept(f)) {
                    decompressFile(new GzipCompressorInputStream(new BufferedInputStream(fis)), f);
                }
            }
        } catch (ArchiveExtractionException ex) {
            LOGGER.warn("Exception extracting archive '{}'.", archive.getName());
            LOGGER.debug("", ex);
        } catch (IOException ex) {
            LOGGER.warn("Exception reading archive '{}'.", archive.getName());
            LOGGER.debug("", ex);
        } finally {
            try {
                fis.close();
            } catch (IOException ex) {
                LOGGER.debug("", ex);
            }
        }
    }

    /**
     * Extracts files from an archive.
     *
     * @param input the archive to extract files from
     * @param destination the location to write the files too
     * @param engine the dependency-check engine
     * @throws ArchiveExtractionException thrown if there is an exception extracting files from the archive
     */
    private void extractArchive(ArchiveInputStream input, File destination, Engine engine) throws ArchiveExtractionException {
        ArchiveEntry entry;
        try {
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    final File d = new File(destination, entry.getName());
                    if (!d.exists()) {
                        if (!d.mkdirs()) {
                            final String msg = String.format("Unable to create directory '%s'.", d.getAbsolutePath());
                            throw new AnalysisException(msg);
                        }
                    }
                } else {
                    final File file = new File(destination, entry.getName());
                    if (engine.accept(file)) {
                        LOGGER.debug("Extracting '{}'", file.getPath());
                        BufferedOutputStream bos = null;
                        FileOutputStream fos = null;
                        try {
                            final File parent = file.getParentFile();
                            if (!parent.isDirectory()) {
                                if (!parent.mkdirs()) {
                                    final String msg = String.format("Unable to build directory '%s'.", parent.getAbsolutePath());
                                    throw new AnalysisException(msg);
                                }
                            }
                            fos = new FileOutputStream(file);
                            bos = new BufferedOutputStream(fos, BUFFER_SIZE);
                            int count;
                            final byte[] data = new byte[BUFFER_SIZE];
                            while ((count = input.read(data, 0, BUFFER_SIZE)) != -1) {
                                bos.write(data, 0, count);
                            }
                            bos.flush();
                        } catch (FileNotFoundException ex) {
                            LOGGER.debug("", ex);
                            final String msg = String.format("Unable to find file '%s'.", file.getName());
                            throw new AnalysisException(msg, ex);
                        } catch (IOException ex) {
                            LOGGER.debug("", ex);
                            final String msg = String.format("IO Exception while parsing file '%s'.", file.getName());
                            throw new AnalysisException(msg, ex);
                        } finally {
                            if (bos != null) {
                                try {
                                    bos.close();
                                } catch (IOException ex) {
                                    LOGGER.trace("", ex);
                                }
                            }
                            if (fos != null) {
                                try {
                                    fos.close();
                                } catch (IOException ex) {
                                    LOGGER.trace("", ex);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new ArchiveExtractionException(ex);
        } catch (Throwable ex) {
            throw new ArchiveExtractionException(ex);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ex) {
                    LOGGER.trace("", ex);
                }
            }
        }
    }

    /**
     * Decompresses a file.
     *
     * @param inputStream the compressed file
     * @param outputFile the location to write the decompressed file
     * @throws ArchiveExtractionException thrown if there is an exception decompressing the file
     */
    private void decompressFile(CompressorInputStream inputStream, File outputFile) throws ArchiveExtractionException {
        LOGGER.debug("Decompressing '{}'", outputFile.getPath());
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(outputFile);
            final byte[] buffer = new byte[BUFFER_SIZE];
            int n = 0;
            while (-1 != (n = inputStream.read(buffer))) {
                out.write(buffer, 0, n);
            }
        } catch (FileNotFoundException ex) {
            LOGGER.debug("", ex);
            throw new ArchiveExtractionException(ex);
        } catch (IOException ex) {
            LOGGER.debug("", ex);
            throw new ArchiveExtractionException(ex);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ex) {
                    LOGGER.trace("", ex);
                }
            }
        }
    }

    /**
     * Attempts to determine if a zip file is actually a JAR file.
     *
     * @param dependency the dependency to check
     * @return true if the dependency appears to be a JAR file; otherwise false
     */
    private boolean isZipFileActuallyJarFile(Dependency dependency) {
        boolean isJar = false;
        ZipFile zip = null;
        try {
            zip = new ZipFile(dependency.getActualFilePath());
            if (zip.getEntry("META-INF/MANIFEST.MF") != null
                    || zip.getEntry("META-INF/maven") != null) {
                final Enumeration<ZipArchiveEntry> entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    final ZipArchiveEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        final String name = entry.getName().toLowerCase();
                        if (name.endsWith(".class")) {
                            isJar = true;
                            break;
                        }
                    }
                }
            }
        } catch (IOException ex) {
            LOGGER.debug("Unable to unzip zip file '{}'", dependency.getFilePath(), ex);
        } finally {
            ZipFile.closeQuietly(zip);
        }

        return isJar;
    }
}
