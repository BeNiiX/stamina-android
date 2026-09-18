# stamina-android — SDK d'attribution STAMINA pour Android (Kotlin)

Une ligne à intégrer (ou zéro), zéro dépendance réseau imposée, rien à brancher : install attribué
(Install Referrer Play Store, liens de tracking via matching), GAID, sessions, revenu par source
d'acquisition. Spécification commune à tous les SDK : `../docs/13-SPEC-SDK.md`.

## Installation

```kotlin
// settings.gradle.kts (JitPack tant que Maven Central n'est pas publié)
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }

// app/build.gradle.kts
implementation("com.github.BeNiiX:stamina-android:1.0.0")   // Maven Central à venir : io.getstamina:stamina-android
```

minSdk 24. Dépendances : `installreferrer`, `play-services-ads-identifier`, `kotlinx-coroutines`,
`androidx.startup`. Permissions `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID` fusionnées automatiquement.

## Usage

**Zéro ligne de code** — dans `AndroidManifest.xml` :

```xml
<application>
    <meta-data android:name="io.getstamina.API_KEY" android:value="stam_android_live_…" />
</application>
```

**Ou une ligne** — dans `Application.onCreate()` (permet de passer un `deviceUserId`) :

```kotlin
Stamina.configure(this, "stam_android_live_…")
```

Puis :

```kotlin
Stamina.sendEvent("login")
Stamina.sendEvent("purchase", params = mapOf("revenue" to 9.99, "currency" to "EUR"))
Stamina.sendEvent("custom", subtype = "level_complete", params = mapOf("level" to 5))
Stamina.trackOnboardingStep("choose_goal", step = 2)   // nom STABLE en snake_case
val attribution = Stamina.attribution()                  // suspend
```

### RevenueCat (obligatoire pour le revenu)

Le SDK n'importe pas RevenueCat : passez-lui un pont de 4 méthodes autour de `Purchases.sharedInstance`,
**après** `Purchases.configure`, et à chaque `logIn` / `logOut` de l'app :

```kotlin
object RevenueCatBridge : StaminaPurchasesBridge {
    override suspend fun isAnonymous() = Purchases.sharedInstance.isAnonymous
    override suspend fun appUserID() = Purchases.sharedInstance.appUserID
    override suspend fun logIn(appUserID: String) { Purchases.sharedInstance.awaitLogIn(appUserID) }
    override suspend fun setAttributes(attributes: Map<String, String>) { Purchases.sharedInstance.setAttributes(attributes) }
}

Stamina.linkRevenueCat(RevenueCatBridge)   // suspend
```

### GAID : rien à faire

Le GAID part avec l'install si le suivi n'est pas limité ; s'il n'était pas disponible à ce moment-là,
le SDK le relit au retour au premier plan et le rattache à l'install, une seule fois.

## API

| Méthode | Rôle |
|---|---|
| `configure(context, apiKey, …)` / `configure(context, StaminaConfig)` | Init non bloquante, idempotente |
| `deviceUserId()` | Id anonyme persisté (à partager avec RevenueCat) |
| `sendEvent(name, subtype?, params?)` | Événement ; `revenue`/`price` + `currency` pour le revenu |
| `trackOnboardingStep(step, stepIndex?, params?)` | Étape d'onboarding typée |
| `setCustomerUserId(id)` | Id interne (opaque/hashé), retry automatique |
| `linkRevenueCat(bridge)` | Identité partagée RevenueCat |
| `attribution()` / `refreshAttribution()` | Attribution de l'install |
| `syncAdvertisingId()` / `trackingStatus` | GAID (facultatif) |
| `sendTestEvent()` / `deviceInfo()` | Diagnostic |
| `flush()` / `clearData()` / `destroy()` | File, reset complet, arrêt |
| `isInitialized` / `isDisabled` | État (kill-switch si la clé est rejetée) |

Les appels non bloquants s'exécutent sur un thread unique du SDK, dans l'ordre d'appel.

## Comportement (identique aux autres SDK STAMINA)

Install envoyé une fois (retry 30 s / 2 min / 10 min) · file persistée 500 événements, lots de 100,
timer 20 s, backoff 30 s → 5 min · sessions automatiques via `ActivityLifecycleCallbacks` (nouvelle
session après 30 min d'arrière-plan, `session_end` avec durée) · kill-switch uniquement sur 401/403 JSON
du serveur · repli d'adresse `api.getstamina.io` → `stamina-tracker.stamina.workers.dev` · stockage
`SharedPreferences` `io.getstamina.sdk` (mêmes clés que le SDK React Native : un appareil qui change de
SDK garde son identité).

## Développement

```bash
scripts/test.sh    # serveur de conformité + ./gradlew :stamina:testDebugUnitTest (Robolectric, JDK d'Android Studio)
```

Tests : 5 unitaires (`EventsTest`) + 9 scénarios de conformité (`ConformanceTest`, faux signaux, vrai
cycle de vie d'activité Robolectric). L'Install Referrer réel n'existe que pour une installation faite
par le Play Store (piste de test interne) : un APK sideloadé donne `null`.
