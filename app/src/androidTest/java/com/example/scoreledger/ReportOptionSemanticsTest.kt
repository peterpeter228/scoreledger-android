package com.example.scoreledger

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.scoreledger.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportOptionSemanticsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun labelAndCheckedStateAreOneClickableCheckbox() {
        compose.setContent{var value by remember{mutableStateOf(true)};ScoreLedgerTheme{ReportOption("隐藏昵称测试",value){value=it}}}
        val option=compose.onNodeWithText("隐藏昵称测试")
        option.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Checkbox)).assertIsOn().performClick().assertIsOff()
    }
}
