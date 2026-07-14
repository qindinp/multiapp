package com.multiapp.core.manifest

import android.content.Context
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PatternMatcher
import com.multiapp.core.model.virtual.ResolvedIntentPathPatternType
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestIntentFilterParsingTest {

    private val parser = ManifestParser(mockk<Context>(relaxed = true))

    @Test
    fun `XML parsing preserves complete intent-filter data semantics`() {
        val manifest = parser.parseFromXml(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.filters">
                <application>
                    <activity android:name=".DeepLinkActivity" android:exported="true">
                        <intent-filter android:priority="42">
                            <action android:name="android.intent.action.VIEW"/>
                            <category android:name="android.intent.category.DEFAULT"/>
                            <data
                                android:mimeType="image/*"
                                android:scheme="https"
                                android:host="api.example.com"
                                android:port="8443"
                                android:path="/literal"
                                android:pathPrefix="/prefix"
                                android:pathPattern="/simple/.*"
                                android:pathAdvancedPattern="/advanced/[0-9]+"
                                android:pathSuffix=".json"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent()
        )

        val filter = manifest.activities.single().intentFilters.single()
        assertEquals(listOf("image/*"), filter.dataMimeTypes)
        assertEquals(listOf("https"), filter.dataSchemes)
        assertEquals("api.example.com", filter.dataAuthorities.single().host)
        assertEquals(8443, filter.dataAuthorities.single().port)
        assertEquals(
            listOf(
                ResolvedIntentPathPatternType.LITERAL,
                ResolvedIntentPathPatternType.PREFIX,
                ResolvedIntentPathPatternType.SIMPLE_GLOB,
                ResolvedIntentPathPatternType.ADVANCED_GLOB,
                ResolvedIntentPathPatternType.SUFFIX
            ),
            filter.dataPathPatterns.map { it.type }
        )
        assertEquals(42, filter.priority)
        assertEquals(listOf("api.example.com"), filter.legacyDataAuthorities)
        assertEquals(
            listOf("/literal", "/prefix", "/simple/.*", "/advanced/[0-9]+", ".json"),
            filter.legacyDataPaths
        )
    }

    @Test
    fun `PackageInfo parsing preserves framework intent-filter semantics for components`() {
        val frameworkFilter = frameworkFilter()
        val packageInfo = PackageInfo().apply {
            packageName = "com.example.filters"
            activities = arrayOf(FilteredActivityInfo(frameworkFilter).apply { name = "$packageName.DeepLinkActivity" })
            services = arrayOf(FilteredServiceInfo(frameworkFilter).apply { name = "$packageName.SyncService" })
            receivers = arrayOf(FilteredActivityInfo(frameworkFilter).apply { name = "$packageName.LinkReceiver" })
        }

        val manifest = parser.parseFromPackageInfo(
            packageInfo,
            mockk<PackageManager>(relaxed = true)
        )

        val filter = manifest.activities.single().intentFilters.single()
        assertEquals(listOf("android.intent.action.VIEW"), filter.actions)
        assertEquals(listOf("android.intent.category.DEFAULT"), filter.categories)
        assertEquals(listOf("https"), filter.dataSchemes)
        assertEquals(listOf("image/*"), filter.dataMimeTypes)
        assertEquals("api.example.com", filter.dataAuthorities.single().host)
        assertEquals(8443, filter.dataAuthorities.single().port)
        assertEquals(
            ResolvedIntentPathPatternType.values().toList(),
            filter.dataPathPatterns.map { it.type }
        )
        assertEquals(37, filter.priority)
        assertEquals(1, manifest.services.single().intentFilters.size)
        assertEquals(1, manifest.receivers.single().intentFilters.size)
    }

    @Test
    fun `PackageInfo without filters does not replace authoritative XML filters`() {
        val xmlManifest = parser.parseFromXml(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.filters">
                <application>
                    <activity android:name=".DeepLinkActivity">
                        <intent-filter android:priority="29">
                            <action android:name="android.intent.action.VIEW"/>
                            <data
                                android:mimeType="application/json"
                                android:scheme="https"
                                android:host="api.example.com"
                                android:port="9443"
                                android:pathPrefix="/v1"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent()
        )
        val packageInfo = PackageInfo().apply {
            packageName = "com.example.filters"
            activities = arrayOf(ActivityInfo().apply { name = "$packageName.DeepLinkActivity" })
        }

        val merged = parser.applyPackageInfoThemeIds(xmlManifest, packageInfo)

        val filter = merged.activities.single().intentFilters.single()
        assertEquals(listOf("application/json"), filter.dataMimeTypes)
        assertEquals(listOf("https"), filter.dataSchemes)
        assertEquals("api.example.com", filter.dataAuthorities.single().host)
        assertEquals(9443, filter.dataAuthorities.single().port)
        assertEquals(ResolvedIntentPathPatternType.PREFIX, filter.dataPathPatterns.single().type)
        assertEquals("/v1", filter.dataPathPatterns.single().path)
        assertEquals(29, filter.priority)
    }

    private fun frameworkFilter(): IntentFilter {
        val authority = mockk<IntentFilter.AuthorityEntry>()
        every { authority.host } returns "api.example.com"
        every { authority.port } returns 8443

        val paths = listOf(
            path("/literal", PatternMatcher.PATTERN_LITERAL),
            path("/prefix", PatternMatcher.PATTERN_PREFIX),
            path("/simple/.*", PatternMatcher.PATTERN_SIMPLE_GLOB),
            path("/advanced/[0-9]+", PatternMatcher.PATTERN_ADVANCED_GLOB),
            path(".json", PatternMatcher.PATTERN_SUFFIX)
        )
        return mockk<IntentFilter>().also { filter ->
            every { filter.countActions() } returns 1
            every { filter.getAction(0) } returns "android.intent.action.VIEW"
            every { filter.countCategories() } returns 1
            every { filter.getCategory(0) } returns "android.intent.category.DEFAULT"
            every { filter.countDataSchemes() } returns 1
            every { filter.getDataScheme(0) } returns "https"
            every { filter.countDataTypes() } returns 1
            every { filter.getDataType(0) } returns "image"
            every { filter.countDataAuthorities() } returns 1
            every { filter.getDataAuthority(0) } returns authority
            every { filter.countDataPaths() } returns paths.size
            paths.forEachIndexed { index, pattern ->
                every { filter.getDataPath(index) } returns pattern
            }
            every { filter.priority } returns 37
        }
    }

    private fun path(value: String, patternType: Int): PatternMatcher =
        mockk<PatternMatcher>().also { pattern ->
            every { pattern.path } returns value
            every { pattern.type } returns patternType
        }

    private class FilteredActivityInfo(
        @JvmField val filter: IntentFilter
    ) : ActivityInfo()

    private class FilteredServiceInfo(
        @JvmField val filter: IntentFilter
    ) : ServiceInfo()
}
