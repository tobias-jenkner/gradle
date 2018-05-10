/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.changedetection.state;

import org.gradle.api.file.RelativePath;
import org.gradle.caching.internal.DefaultBuildCacheHasher;
import org.gradle.internal.hash.HashCode;
import org.gradle.util.GradleVersion;

import java.io.File;

public class GradleModuleFileSnapshot extends RegularFileSnapshot {

    public GradleModuleFileSnapshot(String path, File module) {
        super(path, new RelativePath(true, module.getName()), true, new FileHashSnapshot(hashModule(module), 0));
    }

    private static HashCode hashModule(File module) {
        DefaultBuildCacheHasher hasher = new DefaultBuildCacheHasher();
        hasher.putString(module.getName());
        hasher.putString(GradleVersion.current().getVersion());
        return hasher.hash();
    }
}
