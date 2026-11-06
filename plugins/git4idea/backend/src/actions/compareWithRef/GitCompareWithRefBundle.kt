// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.actions.compareWithRef

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

internal object GitCompareWithRefBundle {
  const val BUNDLE: @NonNls String = "messages.GitCompareWithRefBundle"
  private val INSTANCE: DynamicBundle = DynamicBundle(GitCompareWithRefBundle::class.java, BUNDLE)

  @JvmStatic
  fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any): @Nls String =
    INSTANCE.getMessage(key, *params)

  @JvmStatic
  fun messagePointer(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any): Supplier<@Nls String> =
    INSTANCE.getLazyMessage(key, *params)
}
