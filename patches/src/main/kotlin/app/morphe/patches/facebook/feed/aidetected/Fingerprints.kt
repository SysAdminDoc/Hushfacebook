/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.aidetected

import app.morphe.patches.facebook.feed.isStoryModelAccessor
import app.morphe.patches.facebook.feed.readsStoryFlag
import app.morphe.patches.facebook.feed.storyModelAccessors
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

/**
 * Kept name. The plugin that draws Facebook's own AI label in a feed post's header. The rule reads
 * the flag this plugin reads, from the model it reads it on.
 */
internal const val GEN_AI_TRANSPARENCY_PLUGIN =
    "Lcom/facebook/feed/plugins/header/subtitle/impl/genaitransparency/GenAiTransparencyPlugin;"

/**
 * The GraphQL names behind the rule, keyed the way StoryModels.kt describes.
 *
 * Read from 573, 577 and 580 (2026-09-25): GraphQLStory has one zero-argument method that asks
 * `getCachedModel` for the field keyed `ai_generated_detected_info` (0xb4f9e684) as the type tagged
 * `XFBAIGeneratedDetectedInfo` (0x70da9d19). It is `A0W()` returning `LX/41R;` in 573,
 * `A0W()` returning `LX/3zX;` in 577 and `A0X()` returning `LX/3zi;` in 580, where 577's `A0X()`
 * is the neighbouring `ai_generated_self_disclosure_info` accessor. GenAiTransparencyPlugin.A01
 * calls it and reads the boolean keyed `was_detected_as_ai_generated` (0x723ea5fe) with
 * `getCachedBoolean` in all three. FroggoMorphePatches' 573 filter pointed there first; its names
 * don't carry over, which is why nothing here writes one down.
 */
internal const val DETECTED_INFO_FIELD = "ai_generated_detected_info"
internal const val DETECTED_INFO_TYPE = "XFBAIGeneratedDetectedInfo"
internal const val DETECTED_FLAG = "was_detected_as_ai_generated"

/** Whether [method] is GraphQLStory's accessor of the detected-AI info. */
internal fun isDetectedInfoAccessor(method: Method): Boolean =
    isStoryModelAccessor(method, DETECTED_INFO_FIELD, DETECTED_INFO_TYPE)

/** The detected-info accessors [story] declares. The patch wants exactly one. */
internal fun detectedInfoAccessors(story: ClassDef): List<Method> =
    storyModelAccessors(story, DETECTED_INFO_FIELD, DETECTED_INFO_TYPE)

/**
 * Whether [method] calls [accessor] and then reads [DETECTED_FLAG] with `getCachedBoolean`. That is
 * how GenAiTransparencyPlugin decides a post carries Facebook's detected-AI label.
 */
internal fun readsDetectedFlag(method: Method, accessor: Method): Boolean =
    readsStoryFlag(method, accessor, DETECTED_FLAG)
