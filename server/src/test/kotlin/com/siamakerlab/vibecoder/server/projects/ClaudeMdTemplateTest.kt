package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ClaudeMdTemplateTest {
    @Test
    fun `renders flutter android only project rules`() {
        val text = ClaudeMdTemplate.render(
            ClaudeMdTemplate.ProjectInfo(
                appName = "Flutter Demo",
                packageName = "com.example.flutterdemo",
                projectId = "flutter-demo",
                moduleName = "app",
                debugTask = "assembleDebug",
                projectType = ProjectTypes.FLUTTER,
            )
        )

        assertContains(text, "**Project type**: Flutter")
        assertContains(text, "flutter create --platforms=android .")
        assertContains(text, "flutter build apk --debug")
        assertContains(text, "com.example.flutterdemo")
        assertFalse(text.contains("Default Gradle module"))
    }
}
