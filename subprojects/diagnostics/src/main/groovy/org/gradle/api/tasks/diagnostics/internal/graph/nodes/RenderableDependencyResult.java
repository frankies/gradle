/*
 * Copyright 2012 the original author or authors.
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

package org.gradle.api.tasks.diagnostics.internal.graph.nodes;

import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;

import java.util.LinkedHashSet;
import java.util.Set;

public class RenderableDependencyResult extends AbstractRenderableDependencyResult {
    private final ResolvedDependencyResult dependency;

    public RenderableDependencyResult(ResolvedDependencyResult dependency) {
        this.dependency = dependency;
    }

    @Override
    public boolean isResolvable() {
        return true;
    }

    @Override
    protected ModuleComponentIdentifier getActual() {
        return dependency.getSelected().getId();
    }

    @Override
    protected ModuleComponentSelector getRequested() {
        return dependency.getRequested();
    }

    public Set<RenderableDependency> getChildren() {
        Set<RenderableDependency> out = new LinkedHashSet<RenderableDependency>();
        for (DependencyResult d : dependency.getSelected().getDependencies()) {
            if (d instanceof UnresolvedDependencyResult) {
                out.add(new RenderableUnresolvedDependencyResult((UnresolvedDependencyResult) d));
            } else {
                out.add(new RenderableDependencyResult((ResolvedDependencyResult) d));
            }
        }
        return out;
    }
}
