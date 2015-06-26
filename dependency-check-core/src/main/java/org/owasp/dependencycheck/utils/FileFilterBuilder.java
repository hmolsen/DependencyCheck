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
 * Copyright (c) 2015 Institute for Defense Analyses. All Rights Reserved.
 */

package org.owasp.dependencycheck.utils;

import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.apache.commons.io.filefilter.OrFileFilter;
import org.apache.commons.io.filefilter.SuffixFileFilter;

import java.io.File;
import java.io.FileFilter;
import java.util.*;

/**
 * Utility class for building useful {@link FileFilter} instances for
 * {@link org.owasp.dependencycheck.analyzer.AbstractFileTypeAnalyzer} implementations. The built filter uses
 * {@link OrFileFilter} to logically OR the given filter conditions.
 *
 * @author Dale Visser <dvisser@ida.org>
 */
public class FileFilterBuilder {

    public static FileFilterBuilder newInstance(){
        return new FileFilterBuilder();
    }

    private Set<String> filenames = new HashSet<String>();

    /**
     * Add to the set of filenames to accept for analysis. Case sensitivity is assumed.
     *
     * @param names one or more filenames to accept for analysis
     */
    public FileFilterBuilder addFilenames(String... names) {
        filenames.addAll(Arrays.asList(names));
        return this;
    }

    private Set<String> extensions = new HashSet<String>();

    /**
     * Add to the set of file extensions to accept for analysis. Case sensitivity is assumed.
     *
     * @param extensions one or more file extensions to accept for analysis
     */
    public FileFilterBuilder addExtensions(String... extensions) {
        return this.addExtensions(Arrays.asList(extensions));
    }

    /**
     * Add to the set of file extensions to accept for analysis. Case sensitivity is assumed.
     *
     * @param extensions one or more file extensions to accept for analysis
     */
    public FileFilterBuilder addExtensions(Iterable<String> extensions){
        for (String extension : extensions) {
            // Ultimately, SuffixFileFilter will be used, and the "." needs to be explicit.
            this.extensions.add(extension.startsWith(".") ? extension : "." + extension);
        }
        return this;
    }

    private List<IOFileFilter> fileFilters = new ArrayList<IOFileFilter>();

    /**
     * Add to a list of {@link IOFileFilter} instances to consult for whether to accept a file for analysis.
     *
     * @param filters one or more file filters to consult for whether to accept for analysis
     */
    public FileFilterBuilder addFileFilters(IOFileFilter... filters) {
        fileFilters.addAll(Arrays.asList(filters));
        return this;
    }

    /**
     * Builds the filter and returns it.
     *
     * @return a filter that is the logical OR of all the conditions provided by the add... methods
     * @throws IllegalStateException if no add... method has been called with one or more arguments
     */
    public FileFilter build() {
        if (filenames.isEmpty() && extensions.isEmpty() && fileFilters.isEmpty()) {
            throw new IllegalStateException("May only be invoked after at least one add... method has been invoked.");
        }
        OrFileFilter filter = new OrFileFilter();
        if (!filenames.isEmpty()) {
            filter.addFileFilter(new NameFileFilter(new ArrayList<String>(filenames)));
        }
        if (!extensions.isEmpty()) {
            filter.addFileFilter(new SuffixFileFilter(new ArrayList<String>(extensions)));
        }
        for (IOFileFilter iof : fileFilters) {
            filter.addFileFilter(iof);
        }
        return filter;
    }
}
