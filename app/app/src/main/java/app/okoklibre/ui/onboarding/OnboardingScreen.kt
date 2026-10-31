package app.okoklibre.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.okoklibre.R
import app.okoklibre.algo.Grading
import app.okoklibre.algo.WeightUnit
import app.okoklibre.data.LengthUnit
import app.okoklibre.ui.appViewModel
import app.okoklibre.ui.common.regionLabel
import app.okoklibre.ui.common.weightUnitLabel
import app.okoklibre.ui.design.AppBar
import app.okoklibre.ui.design.Gutter
import app.okoklibre.ui.design.OptionRow
import app.okoklibre.ui.design.PrimaryButton
import app.okoklibre.ui.design.Section
import app.okoklibre.ui.design.SectionGap
import app.okoklibre.ui.family.PersonFields
import app.okoklibre.ui.family.lengthUnitLabel
import app.okoklibre.ui.theme.LocalAppColors

private const val STEP_MS = 260

/** Three steps on one screen: who this app is, who you are, and how you count. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val vm: OnboardingViewModel = appViewModel { OnboardingViewModel(it) }
    val draft = vm.draft
    val colors = LocalAppColors.current
    val progress by animateFloatAsState(
        targetValue = (draft.step.ordinal + 1f) / OnboardingStep.entries.size,
        animationSpec = tween(STEP_MS),
        label = "onboarding progress",
    )

    BackHandler(enabled = draft.step != OnboardingStep.WELCOME, onBack = vm::back)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppBar(title = null, onBack = if (draft.step != OnboardingStep.WELCOME) vm::back else null) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter).height(2.dp),
                color = colors.ink,
                trackColor = colors.hairline,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            AnimatedContent(
                targetState = draft.step,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(STEP_MS)) { direction * it / 12 } + fadeIn(tween(STEP_MS)))
                        .togetherWith(
                            slideOutHorizontally(tween(STEP_MS)) { -direction * it / 24 } + fadeOut(tween(STEP_MS / 2)),
                        )
                },
                label = "onboarding step",
                modifier = Modifier.weight(1f),
            ) { step ->
                when (step) {
                    OnboardingStep.WELCOME -> WelcomeStep(onStart = vm::next)
                    OnboardingStep.YOU -> YouStep(vm)
                    OnboardingStep.UNITS -> UnitsStep(vm, onFinished)
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxSize().padding(horizontal = Gutter)) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painterResource(R.drawable.ic_splash_mark),
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(120.dp),
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.onboarding_stays_on_phone),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
        }
        PrimaryButton(
            stringResource(R.string.onboarding_finish),
            onClick = onStart,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

/** A step's content scrolls; its one action stays put, above the keyboard. */
@Composable
private fun StepPage(
    title: String,
    action: String,
    onAction: () -> Unit,
    busy: Boolean = false,
    caption: String? = null,
    problem: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(horizontal = Gutter),
            )
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.muted,
                    modifier = Modifier.padding(horizontal = Gutter).padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(SectionGap))
            content()
        }
        if (problem != null) {
            Text(
                problem,
                style = MaterialTheme.typography.bodySmall,
                color = colors.attention,
                modifier = Modifier.padding(horizontal = Gutter),
            )
        }
        PrimaryButton(
            action,
            onClick = onAction,
            busy = busy,
            modifier = Modifier.padding(horizontal = Gutter).padding(top = 8.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun YouStep(vm: OnboardingViewModel) {
    val draft = vm.draft
    StepPage(
        title = stringResource(R.string.onboarding_you_title),
        caption = stringResource(R.string.onboarding_you_body),
        action = stringResource(R.string.next),
        onAction = vm::next,
    ) {
        PersonFields(
            form = draft.person,
            onChange = { form -> vm.update { copy(person = form) } },
            lengthUnit = draft.lengthUnit,
            showErrors = draft.showErrors,
            onLengthUnit = vm::chooseLengthUnit,
        )
    }
}

@Composable
private fun UnitsStep(vm: OnboardingViewModel, onFinished: () -> Unit) {
    val draft = vm.draft
    StepPage(
        title = stringResource(R.string.onboarding_units_heading),
        action = stringResource(R.string.onboarding_finish_setup),
        onAction = { vm.finish(onFinished) },
        busy = draft.saving,
        problem = if (draft.failed) stringResource(R.string.person_save_failed) else null,
    ) {
        Section(title = stringResource(R.string.onboarding_units_weight)) {
            OptionRow(
                options = WeightUnit.entries,
                selected = draft.weightUnit,
                label = { weightUnitLabel(it) },
                onSelect = { unit -> vm.update { copy(weightUnit = unit) } },
            )
        }
        Spacer(Modifier.height(SectionGap))
        Section(title = stringResource(R.string.onboarding_height_unit)) {
            OptionRow(
                options = LengthUnit.entries,
                selected = draft.lengthUnit,
                label = { lengthUnitLabel(it) },
                onSelect = vm::chooseLengthUnit,
            )
        }
        Spacer(Modifier.height(SectionGap))
        Section(
            title = stringResource(R.string.onboarding_units_region),
            caption = stringResource(R.string.onboarding_region_caption),
        ) {
            // The region names are long; at a large font size they scroll rather than clip.
            OptionRow(
                options = Grading.Region.entries,
                selected = draft.bmiRegion,
                label = { regionLabel(it) },
                onSelect = { region -> vm.update { copy(bmiRegion = region) } },
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}
