# Changelog

## 1.1.0 — 2026-11-06

- `extras.environment` envoyé avec l'install : `production`, `debug` (build débogable) ou `simulator`. Le
  dashboard ne compte que la production. Les pistes de test du Play Store ne sont pas détectables : elles
  comptent comme production.

## 1.0.0 — 2026-09-21

Première version publique.

- Install attribué : Install Referrer Play Store, GAID (Limit Ad Tracking respecté), Android ID, UA WebView, modèle.
- GAID rattaché à l'install après coup s'il n'était pas disponible au premier lancement.
- Sessions automatiques (30 min) via `ActivityLifecycleCallbacks`, file d'événements persistée (500, lots de 100, backoff), install envoyé une fois avec retry.
- Kill-switch sur clé rejetée (401/403 JSON), repli d'adresse. Aucune dépendance réseau imposée (HttpURLConnection).
- Démarrage sans code : `<meta-data android:name="io.getstamina.API_KEY" …/>` (androidx.startup).
- Pont RevenueCat sans dépendance (`StaminaPurchasesBridge`).
- Conforme à la spécification commune des SDK STAMINA (9 scénarios de conformité).
