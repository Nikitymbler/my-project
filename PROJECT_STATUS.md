# Arena of Nations — Project Status

Документ состояния разработки. Источник правды — **текущий код**, не переписка.
Дата снимка: 31 июля 2026.

---

## 1. Техническая среда

| Параметр | Значение |
|---|---|
| Проект | `C:\Users\pavel\Desktop\ArenaOfNations` |
| Minecraft | **1.21** |
| Платформа | **Fabric** |
| Fabric Loader | **0.19.3** |
| Fabric API | **0.102.0+1.21** |
| Loom | **1.17-SNAPSHOT** |
| Java | **21** |
| Mappings | **Official Mojang Mappings** |
| Mod ID | `arena_of_nations` |
| Package | `com.nikita.arenaofnations` |
| Версия мода | `1.0.0` |
| Source sets | `splitEnvironmentSourceSets()` (`main` + `client`) |

Сборка: `gradlew.bat build`. Не запускать Minecraft / `runClient`, если не запрошено явно.

---

## 2. Основная концепция

Зрители поддерживают страны подарками; сила превращается в бойцов и резерв; страны сражаются на арене.

- Внешняя интеграция StreamToEarn: **этап 1 (аудит) выполнен по коду**; реальный TikTok-эфир ещё не подключался.
- В коде есть HTTP/S2E/viewer-мост (`ArenaStreamToEarnHttpBridge`, `ArenaStreamToEarnCommands`, `ArenaViewerEventManager`).
- Текущий фокус — создание и улучшение самой игры (бой, классы, арена, UI, зрелищность, баланс).

---

## 3. Страны (20 в одном раунде)

`Country` — 20 стран с **стабильным string id** (не ordinal) для NBT/score/HUD:

| id | код | страна |
|---|---|---|
| ru | RU | Россия |
| ua | UA | Украина |
| by | BY | Беларусь |
| kz | KZ | Казахстан |
| lt | LT | Литва |
| pl | PL | Польша |
| il | IL | Израиль |
| am | AM | Армения |
| uz | UZ | Узбекистан |
| tj | TJ | Таджикистан |
| ge | GE | Грузия |
| kg | KG | Кыргызстан |
| tm | TM | Туркменистан |
| md | MD | Молдова |
| az | AZ | Азербайджан |
| lv | LV | Латвия |
| al | AL | Албания |
| bg | BG | Болгария |
| cn | CN | Китай |
| us | US | США |

- Теги: `country_<id>`. Команды/score: `arena_<id>`.
- `Country.byId(String)` — парсинг ru/ua/… или RU/UA/…
- `Country.ALL` — полный реестр; **не** использовать `Country.values()` там, где нужны только участники раунда.
- `MAX_ACTIVE_COUNTRIES = 20` (`ArenaCountryBaseLayout`); 21-я страна отклоняется с сообщением → очередь следующего раунда.
- Старые сохранения ru/ua/kz/by загружаются по string id; новые страны инициализируются с 0 очков.

### Базы стран (физическая арена v3/v4)

`ArenaCountryBaseLayout`: **20 постоянных физических слотов** по окружности (шаг **18°**). Слот назначается при входе в раунд и **не меняется** до reset.

**Радиусы (актуальные):**

| Зона | Значение |
|---|---:|
| Центр-рисунок (`CENTER_PATTERN_RADIUS`) | **42** |
| Проходимое поле (`COMBAT_WALKABLE_RADIUS`) | **64** |
| Spawn zone (`SPAWN_ZONE_RADIUS`) | **52** |
| Spawn ring | **54** |
| Core attack | **60** |
| Core / крепость (`CORE_RING_RADIUS`) | **67** |
| Внешняя стена | **86** |
| Трибуны inner/outer | **74–82** |
| Очистка (`CLEAR_RADIUS`) | **92** |

**Расстояние между соседними базами:** `2 × 67 × sin(9°) ≈ 20.9` блока. Base v4 ширина **13** + зазор **3** → влезает без увеличения арены.

**Base v4** (`ArenaCoreBuilder`): крепость **13×9×10** — две башни 3×3, центральные ворота 3 блока, видимый core (lodestone), пьедестал, battlements; цвет страны ≤15–20% (полосы на башнях, trim у core). Состояния: INACTIVE / ACTIVE / DAMAGED / DESTROYED — обновление только при смене state. Код страны — `ArenaBaseCodeDisplay` (ArmorStand label над воротами).

**Spawn zones:** 2 ряда × 5 точек (10 nominal, min **8** safe) на плоской платформе **11×6** между базой и центром; `collectSafeSpawnFeet` + `resolveSpawnPoint` с inward fallback.

**Path validation:** `ArenaLayoutPathfinder` — временный `ArenaFighterEntity` probe, `PathNavigation.createPath` к 5 целям центральной зоны, `path.canReach()`, probe удаляется (тег `arena_of_nations.layout_path_probe`).

**Layout validation (strict):** `ArenaCountryLayoutValidator` — слот valid только при `coreBuilt && safePoints≥8 && pathToCenter`. Проверка пути идёт по всем safe-точкам и по нескольким центральным целям; `spawn outside walkable field` проверяется по `COMBAT_WALKABLE_RADIUS` (а не по декоративному радиусу центра).

Команды: `/arena_country_layout_status`, `/arena_country_layout_validate` (summary: Valid slots, Safe spawn points, No path, Intersections), `/arena_country_layout_debug on|off`.

`ArenaPositions`: `CENTER_PATTERN_RADIUS=42`, `COMBAT_WALKABLE_RADIUS=64`, `OUTER_RADIUS=86`, `CLEAR_RADIUS=92`. Причина старой ошибки `spawn outside field`: ранее `FIELD_RADIUS` (42) одновременно использовался как граница walkable-арены.

---

## 4. Классы бойцов

Упрощённый боевой профиль (сессия 28.07):

- В геймплее спавнится только один класс: `FighterTier.SCOUT` с display **«Боец»**.
- `/arena_gift <country> <coins>`: `coins >= 1` создаёт **ровно coins бойцов**.
- Подарки не капятся лимитом живых: каждый gift превращается в `coins` pending-бойцов в резерве.
- Спавн на поле идёт волнами в `BATTLE`: `reserveReleaseBatch` / `reserve_wave_size` за тик волны (по умолчанию **10**, live 1–100).

Параметры единственного класса (SCOUT-профиль из `ArenaFighterBalance`):

| Параметр | Значение |
|---|---:|
| HP | 10 |
| Урон | 2.5 |
| Speed | 0.36 |
| Attack cooldown | 7 |
| KB resist | 0.00 |
| Visual scale | 0.85 |
| Оружие (visual) | `arena_of_nations:medieval_spear` (глефа) |

### Отключено из live-геймплея

- Пороговый выбор tier по монетам (10/50/200/1000) — не используется; `ArenaConfig.tierFromCoins(...)` возвращает `SCOUT`.
- Классовые способности ELITE/CHAMPION/TITAN не применяются в уроне: `ArenaFighterEntity.doHurtTarget()` вызывает только `super.doHurtTarget`.
- `ArenaTierDamageScaling` и `ArenaSwarmDamageProtection` не участвуют в `ArenaFighterEntity.hurt()` (прямой урон без multipliers/budget).
- Старые ability/scaling/swarm классы оставлены в кодовой базе как архив/диагностика, но сняты с текущего боевого пути.

### Economy tiebreak

Economy-тесты: `ArenaEconomyTest.BATTLE_SECONDS = 180` (флаг `economyTestBattle` в `ArenaMatchManager`, обычный конфиг раунда не меняется).

**Победитель по таймеру** (все бои, включая economy-тесты): только по вышкам:

1. процент HP главной вышки (выше — лучше)
2. суммарный урон, нанесённый вышкам противников (`ArenaCoreManager.coreDamageDealt`)
3. ничья

Не используются: число бойцов, HP бойцов, gift cost, effective combat value, `ArenaEconomicArmyValue` (остаётся для `/arena_economy_status`).

Команды: `/arena_balance_status`, `/arena_economy_status`, `/arena_swarm_status` (permission 2).

Entity type: `arena_of_nations:arena_fighter` (`ArenaFighterEntity extends Wolf`).

### Классовые способности

- В live-геймплее способности выключены.
- `/arena_class_status` сообщает, что активен упрощённый режим с одним классом.
- Ability-классы (ELITE/CHAMPION/TITAN) и их VFX-код сохранены в проекте как legacy-контекст и не вызываются в боевом пути.

---

## 5. Модель и рендер бойцов

- Клиент: широкая **PlayerModel** (`ArenaFighterHumanoidModel`, `slim=false`).
- Слой модели: `ArenaFighterModels.HUMANOID_LAYER`.
- Внешние слои скина включены (hat/jacket/sleeves/pants).
- **Единый скин** всех бойцов: `textures/entity/fighter/medieval_soldier.png` (Mullraugh, 64×64 Steve atlas, в ресурсах и в jar); per-country skin path не используется в live-рендере.
- Если ресурс отсутствует, `ArenaFighterVisuals` пишет **один ERROR** в лог и не переключает бойца на `DefaultPlayerSkin/SkinManager` (раньше это давало purple/black missing texture).
- **Плащ отключён полностью:** active cape layer удалён из `ArenaFighterRenderer`; `ArenaFighterCapeLayer` и `cape_mask.png` удалены; `/arena_visual_status` и `/arena_visual_status_client` показывают `capeEnabled=false`, `capeRenderLayers=0`, `capeResourcesLoaded=0`.
- Резолв текстур скина кэшируется; кэш сбрасывается на resource reload.
- Старые Blockbench-модели (в т.ч. `RuScoutModel`) **не используются** в активном renderer.
- Overhead флаги бойцов: **один** путь `ArenaFighterRenderer → ArenaFighterOverheadRenderer`; рамка/флаг/HP разведены по Z; hysteresis дистанции 40→42; высота флага стабильна (visual scale, не bbHeight).

Ключевые файлы: `ArenaFighterRenderer`, `ArenaFighterHumanoidModel`, `ArenaFighterVisuals`, `ArenaFighterOverheadRenderer`, `ArenaOfNationsClient`.

### Большие флаги баз

- Не entity / не block display: клиентский world billboard `ArenaBaseMarkerRenderer` (WorldRenderEvents.AFTER_ENTITIES).
- Видимость: `ArenaBaseFlagVisibility` — участник раунда **и** не eliminated **и** `baseSlot>=0`. Не зависит от living/reserve/core HP/shield.
- После elimination флаг скрывается; при RESCUE остаётся; после reset/BREAK snapshot очищается.

Диагностика: `/arena_visual_status` (server), `/arena_visual_status_client` (client).

---

## 6. Оружие

Только визуал (`ArenaFighterEquipmentVisuals` + `ArenaFighterHeldItemLayer`):

| Класс | Main | Off |
|---|---|---|
| Боец (SCOUT) | `arena_of_nations:medieval_spear` (глефа) | — |

- Не кладётся в equipment slots.
- Не меняет урон / loot / атрибуты.
- `translateToHand` от parent `PlayerModel`.
- Активный путь: `ArenaFighterHeldItemLayer` + `ItemInHandRenderer` (custom weapon layer не используется).
- Ресурсы: thin low-poly JSON `models/item/medieval_spear.json` (7 cuboids: shaft/wrap/ferrule/blade×3/ridge) + `textures/item/medieval_spear.png` **32×32**.
- Display thirdperson scale **0.92**, layer angle **-20°**, layer scale **1.05**; `weaponVisualType=MEDIEVAL_GLAIVE`, `weaponRenderPaths=1`, `tridentRenderPaths=0`.

---

## 7. Анимация и AI боя

- `ArenaFighterEntity` пока наследует `Wolf`.
- `registerGoals()` **не** вызывает `super.registerGoals()`.
- Нет `LeapAtTargetGoal`, Sit/Beg/FollowOwner, волчьих target goals.
- Goals: `FloatGoal`, `ArenaFighterMeleeAttackGoal` (**свой Goal**, прямой chase; не extends `MeleeAttackGoal`), `LookAtPlayerGoal`, `RandomLookAroundGoal`.
- Цели: `FighterTargeting` (не targetSelector); living chase только melee goal (`moveTo(target)`).
- **Living search radius = 80** (AABB `getEntitiesOfClass`, не полный scan всех entity). Attribute `FOLLOW_RANGE=96` на chase living не влияет.
- **Приоритет BATTLE:** (1) живой враг ≤80; (2) уязвимое ядро → `pursueCore`; (3) иначе **rally** к mid-field точке (~центр + 12 к вражескому approach) без урона по защищённому ядру; (4) idle только если врагов нет.
- Противоположные spawn zones (~**94–104** блоков) вне living radius → без rally армии стояли у базы при защищённых ядрах.
- Прыжок при атаке убран.
- **Sticky target:** `FighterTargeting` сохраняет живую цель в радиусе **14** блоков; не меняет цель во время `meleeWindupActive`; переключение только при смерти/удалении/elimination/выходе за радиус.
- Один `mob.swing(InteractionHand.MAIN_HAND, true)` в начале атаки (melee windup и core windup); в `doHurtTarget` swing нет.
- Анимация удара — **ванильный** swing `PlayerModel` через `attackTime` (ArmPose ITEM/EMPTY, без своей трёхфазной анимации).
- **`ArenaFighterEntity.aiStep` вызывает `updateSwingTime()`** — у `Player` это делает `serverAiStep`/client tick; у `Wolf` вызова нет, поэтому без этого `attackAnim` оставался 0 и рука не махала (выглядело как «укус»).
- `getMainArm()` → `HumanoidArm.RIGHT`.
- Задержка урона: `WINDUP_TICKS = LivingEntity.SWING_DURATION / 2` → **3 тика** после swing.
- **Melee reach (реализовано):** `ArenaFighterMeleeRange` — start **2.35** XZ center, confirmation **2.95**, vertical max **2.0**, humanoid LoS (eye Y+1.25 → target Y+1.0 + vanilla sensing). Edge distance только для диагностики. Не опирается на vanilla `Mob.isWithinMeleeAttackRange` как единственный gate.
- **Melee goal (реализовано):** `ArenaFighterMeleeAttackGoal` только с `Goal.Flag.LOOK` (**без** `MOVE`) — иначе GoalSelector останавливал goal при `navigation.isDone()` и windup не стартовал. Fast repath 2 тика при nav done вне range.
- **Windup/cooldown:** полный tier cooldown только после завершённого windup + `doHurtTarget`; range/LoS cancel → retry **4** тика; target dead → **2** тика; cooldown **не** стартует при начале swing.
- **Navigation:** repath раз в **5** тиков или при смещении цели >1 блока; в start range — `navigation.stop()`.
- **Диагностика:** `ArenaMeleeDiagnostics`, `ArenaFighterMeleeStats` на сущности; `/arena_melee_status` (permission 2); swarm suppression отделена от AI cancel.
- **Test placement (реализовано):** `ArenaTestMeleePlacement` — `melee_contact` линии X=±4, Z шаг 2.0; `melee_density` зоны X=±6..±10, сетка 4×5; валидный пол/`noCollision`/AABB. Поиск бойцов по стране (один класс SCOUT). Сценарии melee/core спавнят бойцов на поле через `spawnTestFightersOnField` (обход резерва).
- Cooldown атаки в live-режиме берётся из `ArenaFighterBalance` через `getArenaTier().getAttackCooldownTicks()`; для единственного класса (SCOUT/«Боец») = **7** тиков.
- Friendly fire: своя страна не выбирается целью.

### Эксперимент боевого позиционирования — УДАЛЁН

Удалены `ArenaFighterCombatPositioning`, `ArenaFighterCombatDimensions`, `ArenaCombatSlotRegistry`, команды `/arena_positioning_*`, сценарий `positioning`.
Вернут простой прямой chase к цели и `getCoreAttackPosition` для ядер. Формации/slots/zones не планируются повторно без отдельного решения.

---

## 8. Клиентские эффекты и зрелищность боя

Только particles/sounds — **без** изменений damage/cooldown/reach/AI.

**Сервер (`ArenaCombatSpectacle`)** — live-путь одного класса «Боец»:
- melee swing → `SWEEP_ATTACK` + `CRIT` + `PLAYER_ATTACK_SWEEP`;
- melee hit → `DAMAGE_INDICATOR` + `CRIT` + `SWEEP_ATTACK` + `PLAYER_ATTACK_STRONG`;
- death → `POOF`×18 + `CRIT`×14 + `SMOKE`×8 + `CLOUD`×6 + crit/extinguish sounds;
- core hit → усиленные `CRIT`/`DAMAGE_INDICATOR`/`SMOKE` + `STONE_HIT` + тихий `ANVIL_PLACE`.

Вызовы: `ArenaFighterMeleeAttackGoal`, `ArenaFighterEntity.playDeathFeedbackOnce`, `ArenaCoreCombatManager.playAttackEffects`. Loot/XP по-прежнему не дропаются.

`ArenaFighterVisualEffects` (клиент, радиус ~**96**):

| Tier | Эффект |
|---|---|
| SCOUT (live) | движение: `CLOUD` у ног (интервал 6); атака: `CRIT`×8 + `DAMAGE_INDICATOR` + `SWEEP_ATTACK` к цели |
| WARRIOR | `CRIT` при атаке ×5 |
| HEAVY | `CRIT` ×6 + `SMOKE` ×2 при атаке |
| HERO | idle `ENCHANTED_HIT` ×2 (интервал 15); атака `CRIT` ×6 |
| TITAN | движение `POOF` ×3 (интервал 5); атака `CRIT` ×8 |

Движение: `xOld`/`zOld`. Состояние чистится при выгрузке сущности и `DISCONNECT`.

---

## 9. Флаги и здоровье над бойцами

- Vanilla name tag скрыт (`shouldShowName` → false; пустой `renderNameTag`).
- `customName` сущности **сохранён** (`FighterFactory`: «Страна — Класс»).
- Billboard-флаги всех 20 стран: PNG **64×40** в `assets/arena_of_nations/textures/gui/flags/<id>.png` (ru…us).
- HUD round: blit 64×40 → 14×8; fallback на код страны только при отсутствии/битом ресурсе (без спама в лог каждый кадр).
- **HUD с 1–2 строками при тесте 1–2 стран — нормальное поведение** (не баг); строк = число activeCountries.
- Масштаб индикатора по tier: 0.90 / 1.00 / 1.08 / 1.16 / 1.28; base billboard **0.018**; half-size **14×8.75**.
- Высота: `max(bbHeight, 1.8×visualScale) + 0.48` (+0.05 TITAN).
- ≤16 блоков: флаг + HP; 16–28: только флаг; >28: ничего (`distanceToSqr`).
- HP: слева направо; >60% зелёный; ≥30% жёлтый; иначе красный.
- Flag: `entityCutoutNoCull`; HP: `entityTranslucent(white_pixel)`; depth test включён (не see-through).
- Проверено в игре: флаги и HP работают.

Файлы: `ArenaFighterFlagVisuals`, `ArenaFighterOverheadRenderer`, `ArenaFighterRenderer`.

---

## 9b. CLIENT FPS OPTIMIZATION

Клиентские helpers для частиц/config сохранены, но **визуал флагов над бойцами снова стабильный**:

- Renderer: **`ArenaFighterRenderer`** — overhead flags **каждый render frame** (без LOD/budget gating).
- Причина отката gate: LOD NEAR-only + max 20 nameplates давали мерцание флагов в движении.
- Vanilla frustum culling; без custom distance `shouldRender` на бойцах.
- Particle budget / `arena_of_nations-client.properties` / `/arena_client_perf` остаются для частиц и диагностики.
- Копья и shared skin без изменений.

---

## 10. Раундовая система

`ArenaMatchState`: `IDLE` → `WAITING_FOR_OPPONENT` → `BATTLE` → `BREAK` → …

Из `ArenaMatchManager` + `ArenaConfig` (defaults):

- Waiting ~**60** с, battle ~**600** с, break ~**15** с; вход новых стран закрывается за ~**120** с до конца боя.
- Первый подарок активирует страну/ядро и спавнит бойцов; первый подарок **другой** страны → старт боя.
- Waiting timeout: очистка бойцов/резерва; solo holder → 1 очко hold; затем BREAK.
- Late/`nextRoundQueue`: подарки исключённой страны, новой страны после закрытия входа, любые подарки в BREAK.
- Конец по таймеру или elimination (≤1 активная страна) → announce → `clearAllFighters` + `clearReserves` → BREAK.
- Живые бойцы после боя **не сохраняются** (discard); очередь следующего раунда хранит pending gifts.

---

## 11. Резерв

Внутри `ArenaMatchManager` + live-настройка `ArenaReserveRuntimeSettings`:

- `/arena_gift <country> <coins>` создаёт `coins` pending-бойцов (класс «Боец»); каждый pending проходит через тот же лимит поля/резерва.
- Ограничение `max_waiting_fighters` в конфиге не используется как live-кап поля.
- Переполнение gift → `Queue<PendingFighter>` резерва страны (подарки всегда в резерв, в т.ч. при полном поле WAITING).
- Волны резерва идут в **WAITING** и **BATTLE** каждые `reserve_wave_interval_ticks` (default **40`), до `reserveReleaseBatch` (`reserve_wave_size`, default **10**, диапазон **1–100`) на страну.
- **WAITING:** жёсткий лимит `WAITING_FIELD_LIMIT = 25` живых бойцов ожидающей команды на поле (`ArenaReserveReleaseMath`). Считаются только живые entities на поле; не gifts / reserve / fightersSentThisRound. Формула: `actualRelease = min(batch, reserve, availableActiveSlots, waitingRemainingSlots)`; последняя волна урезается; при 25 на поле волны выпускают 0. При старте BATTLE лимит снимается, уже вышедшие бойцы остаются и получают боевой AI.
- **BATTLE:** `actualRelease = min(configuredBatch, reserve, availableActiveSlots)`; live-field без капа живых (`availableActiveSlots = Integer.MAX_VALUE`). Batch live из `ArenaReserveRuntimeSettings`. Резерв уменьшается только на успешно заспавненных.
- Очистка: timeout waiting, конец боя, elimination, round reset.
- Проверка: `/arena_test_scenario reserve`.

---

## 12. Ядра стран

Подтверждено кодом:

- Позиции (`ArenaCountryBaseLayout` / `ArenaPositions`): **slot-based** — core ring **58**, approach **52**, spawn zone **46**; pedestal ниже ядра. Старые cardinal RU/UA/KZ/BY координаты **не используются**.
- HP ядра: `core_max_health` default **200**; DAMAGED ≤50%.
- **Регенерация ядра (только BATTLE):** `ArenaCoreRegenMath` + timestamps в `ArenaCoreState` (`lastCoreDamageGameTime`, `lastCoreRegenGameTime`). Тик `ArenaCoreManager.tickCoreRegen` из `tickBattle`. Условия: активный участник (`getActiveCountries`), не eliminated, ядро active, HP &gt; 0 и &lt; max, ≥15с (300 ticks) без фактического урона, интервал 5с (100 ticks), +5 HP с капом `min(max, hp+5)`. Разрушенное ядро (HP≤0) не возрождается. WAITING/BREAK/IDLE не вызывают тик. Фактический урон в `applyDamage` обновляет `lastCoreDamageGameTime` (gameTime). Reset/activate/eliminate очищают timestamps. Диагностика в `/arena_core_combat_status`.
- **Защита вышки:** `ArenaCoreManager.isCoreProtected(level, country)` — чистый запрос (без side effects); всегда считает защитников на **fight level** (`ArenaSpawns.resolveFightLevel`). Резерв без активной сущности **не** защищает. Сообщения о смене статуса — только из `updateCoreProtectionStates(server)` (один раз за server tick в `ArenaMatchManager.tickBattle`, после release резерва). `FighterTargeting` / HUD / команды статуса сообщения **не** отправляют.
- Атака ядра (`ArenaCoreCombatManager`): range **3.5**, cooldown **20** тиков, windup **3** тика (`SWING_DURATION/2`), урон = `ATTACK_DAMAGE` бойца через `damageFromFighter`; подход — `getCoreAttackPosition`. Защищённую вышку AI **не атакует** (`findNearestAttackableCore` + проверки в windup/уроне); при отсутствии living/уязвимого ядра идёт **rally** (`rallyTowardEnemyFront`, `rallyOnly`) без урона. Заблокированный урон не попадает в `coreDamageDealt`. Операторский `/arena_core_damage` обходит защиту.
- **Elimination / recovery (вариант C, `ArenaCoreRescueManager`):**
  - ≥1 живой боец страны на fight level → elimination/countdown **не** стартуют (даже при ядре 0%).
  - Ядро 0% + есть бойцы → countdown нет; client HUD **ЯДРО СБИТО** (не «ВЫБЫЛА»).
  - Ядро 0% + 0 бойцов → countdown `core_rescue_seconds` (default **30**); HUD **СПАСЕНИЕ Ns**.
  - Gift при разрушенном ядре → heal на `core_rescue_health_percent` (default **50%** max HP) + сброс timer (и при живых бойцах, и во время countdown).
  - Появление бойца или ядро HP > 0 во время countdown → сброс timer.
  - Expiry при всё ещё 0 HP / 0 бойцов → `eliminated=true`, chat+actionbar «✖ … выбыла», `onCountryEliminated` (clear fighters/reserve/combat links), HUD **ВЫБЫЛА**.
  - Устарело / неверно: «донат обязателен, чтобы не вылететь при живых бойцах»; «ВЫБЫЛА = eliminated \|\| coreHP≤0».
- Команды: `/arena_cores_build confirm`, `/arena_cores_status`, `/arena_core_damage`, `/arena_core_heal`, `/arena_cores_reset`, `/arena_core_combat_status`, `/arena_core_damage_stats`, `/arena_rescue_status`.
- Сценарии: `core_attack`, `core_protection`, `core_unprotected_attack`, `core_rescue`, `core_elimination`.

---

## 13. Урон, победитель и очки

- Mixin `LivingEntityHurtMixin` → фактическая потеря HP/absorption → `ArenaDamageTracker` → `ArenaMatchManager.addDamage` (боец→боец, не игроки, не своя страна).
- Урон по вышкам: `ArenaCoreManager.coreDamageDealt` — только фактическое снижение HP вышки после успешного `damageFromFighter`.
- Победитель по таймеру: (1) % HP вышки → (2) урон по вышкам → (3) ничья. Наличие защитников не даёт автопобеду.
- Ничья: оба критерия равны / нет eligible; очки за ничью не выдаются.
- Очки (`ArenaScoreManager`): hold **1**; duel (2 участника) **3**; multi (≥3) **5**.
- **roundWins** (отдельно от очков): +1 при явной победе раунда (`awardBattleWin` / `awardHold`); ничья не начисляет; NBT compound `roundWins` в `ArenaScoreSavedData`.
- SavedData: `ArenaScoreSavedData` (`arena_of_nations_scores`), `ArenaSetupSavedData` (`arena_of_nations_setup`).

---

## 14. Арена

`ArenaPositions` / `ArenaCountryBaseLayout` (physical v4):

- `CENTER_PATTERN_RADIUS=42`, `COMBAT_WALKABLE_RADIUS=64`, `SPAWN_ZONE_RADIUS=52`, `CORE_RING_RADIUS=67`, `OUTER_WALL_RADIUS=86`, `CLEAR_RADIUS=92`, `WALL_HEIGHT=12`.
- **20** физических баз-крепостей; ворота открыты в центр, центральный бордюр без коллизии (inner ring walls убраны).
- Build rate: `BLOCKS_PER_TICK = 500`. Стадии: clear → foundation → floor (до walkable radius) → sectors → inner ring (skip/no collision) → stands (74–82) → outer wall (86) → portal markers → lighting → **CORES (20 bases)** → finalize.
- Build version: `ArenaSetupSavedData.CURRENT_BUILD_VERSION = 2`.
- Команды: `/arena_build confirm`, `/arena_build_status`, `/arena_cancel_build confirm`, `/arena_setup_clear confirm`, `/arena_rebuild confirm` (safe rebuild: остановка раунда + очистка footprint + запуск новой сборки).
- Layout: `/arena_country_layout_status`, `/arena_country_layout_validate`, `/arena_country_layout_debug on|off`.

---

## 15. Команды

Все команды — **плоские** root literals (`arena_*`), не `/arena ...`.

### Базовые
- `/arena_test`
- `/arena_duel`

### Раундовые / match
- `/arena_gift <country> <coins>`
- `/arena_status`
- `/arena_round_reset`
- `/arena_config_reload` (перечитать `run/config/arena_of_nations.properties` после ручного изменения лимитов/волн)
- `/arena_viewer_chat|gift|status|reset`
- `/arena_s2e_chat|gift|status` — ingress-диагностика StreamToEarn (без токена в выводе)
- `/arena_test_scenario s2e_local_gift` — локальный тест chat→gift→dedup без эфира
- `/arena_test_scenario s2e_bridge` — мост двух зрителей RU/UA
- `/arena_s2e_chat|gift|status` (код есть; фокус интеграции отложен)

### Спавн
- `/arena_spawn <country>` (спавнит только класс «Боец»)
- `/arena_team_duel`
- `/arena_clear`
- `/arena_demo_four`

### Тестовые сценарии
- `/arena_test_scenario [reset|…|twenty_countries|twenty_countries_mass|countries_joining|mass_duel_reserve|full_country_lifecycle|base_flag_lifecycle|base_exit_pathing|…]`
- `/arena_test_scenario mass_duel_reserve` — gift RU/UA по 1000 через резерв, волны, march, living target, melee; авто `MASS DUEL RESERVE: PASS|FAILED`
- `/arena_lifecycle_status` / `/arena_ai_status` — living/reserve/wave/rally/nav диагностика (без tick spam)
- `/arena_country_layout_status` — слоты/углы/core/spawn/path участников и все 20 слотов если раунд пуст
- `/arena_country_layout_validate` — полная проверка 20 слотов (пересечения, spawn, path, cores)
- `/arena_country_layout_debug on|off` — частицы base/core/spawn/path (оператор)

### Клиент FPS
- `/arena_client_perf` — LOD/culling/particle counters
- `/arena_client_config_reload` — только `arena_of_nations-client.properties`

### AI / классы / баланс
- `/arena_ai_status`
- `/arena_melee_status`
- `/arena_class_status` (permission 2) — в упрощённом режиме сообщает: способности отключены, класс один
- `/arena_balance_status` (permission 2) — баланс/диагностика
- `/arena_economy_status` (permission 2) — gift/combat value по странам, резерв, урон, таймер
- `/arena_swarm_status` (permission 2) — legacy-диагностика (в live-геймплее swarm не применяется)

### Ядра
- `/arena_cores_build [confirm]`, `/arena_cores_status`, `/arena_core_damage`, `/arena_core_heal`, `/arena_cores_reset`, `/arena_core_combat_status`, `/arena_core_damage_stats`, `/arena_rescue_status`

### Очки
- `/arena_scores`, `/arena_scores_reset`, `/arena_damage_stats`

### Строительство
- `/arena_build [confirm]`, `/arena_build_status`, `/arena_cancel_build [confirm]`, `/arena_setup_clear [confirm]`, `/arena_rebuild [confirm]`

### Диагностика / HUD
- `/arena_hud [on|off|toggle|status]`
- `/arena_hud mode external|minimal|full|off`
- `/arena_hud bossbar [on|off|status]` — legacy BossBar (по умолчанию **off**, только отладка)
- `/arena_hud_debug`, `/arena_hud_status`
- `/arena_overlay_status`, `/arena_overlay_restart`, `/arena_overlay_dump`
- `/arena_base_markers on|off|status` — client world markers (default **on**)
- `/arena_lifecycle_status` — статус `full_country_lifecycle`

### Client round HUD (основной UI раунда)
- Источник: S2C `round_hud` (`ArenaRoundHudSync` / `ArenaHudSnapshot`); renderer `ArenaRoundHudRenderer` + `ArenaHudLayout` **v3**.
- Пакет всегда содержит список стран + `arenaCenter` (для world markers), даже в режиме `external`.
- Режимы отображения (`/arena_hud mode`): `external` (по умолчанию), `minimal`, `full`, `off`.
- `external`: заголовок/таймер + «Стран: N» + rescue alert; таблица стран скрыта (для TikTok overlay).
- `minimal`: то же компактное содержимое.
- `full`: полный список стран в HUD (режим отладки).
- `off`: 2D HUD не рисуется; **base markers остаются** (отдельный client renderer).
- **Full-layout:** 1–4 страны — крупные карточки (≤2 колонки); 5–10 — 2×5 compact; **11–20 — 4 колонки × 5 строк** (не 2×10), row ~18–20px, column ~205–235px, компактный фон только вокруг содержимого строки.
- Стабильный порядок по `baseSlot` (без пересортировки по статусу).
- Ultra-строка: `[FLAG][CODE][A/R][HP bar 50–65px][%][Щ/!/Ns/×]`.
- Bounds: `bottomLimit = guiHeight - 40` (hotbar safe); `clippedRows` / `overlap` в layout metrics.
- Client debug: `/arena_hud_debug_client` — guiWidth/Height/Scale, columns, rows, bounds, overlap, clippedRows.
- BossBar не основной (`bossBarEnabled=false`).

### World-space base markers (Minecraft)
- Client renderer: `ArenaBaseMarkerRenderer` (WorldRenderEvents.AFTER_ENTITIES), без лишних entity.
- Данные: тот же `ArenaHudSnapshot` (country, baseSlot, core HP, status, rescue, eliminated, arenaCenter).
- HD PNG: `textures/gui/flags_hd/<id>.png` **256×160** (из overlay SVG).
- Флаг в мире ≈ **5.0 × 3.0** блока; позиция: core + inward **2** + above **11**.
- Render: `entityCutoutNoCull` + FULL_BRIGHT; billboard `scale(-1,-1,1)`; рамка **за** флагом; текст Font отдельно.
- **Над флагом:** отдельный world-billboard `Country.getDisplayName()` (`labelY = flagCenterY + 1.5 + 0.85`), `SEE_THROUGH` + FULL_BRIGHT; не в локальной матрице флага.
- Видимость названия = видимость флага: `CURRENT_ROUND_PARTICIPANTS` && !eliminated && slot>=0; RESCUE оставляет; ELIMINATION/reset убирает. Не все 20 баз.
- Под флагом: «ЯДРО hp/max», полоска HP (~5.4×0.36), статус (ЩИТ / УЯЗВИМА / …).
- Дистанция: полный до **100**, fade до **120**.
- Depth test включён; тонкая тёмная рамка; default **on** (`/arena_base_markers`).
- Legacy `ArenaCoreDisplayManager` TextDisplay и `ArenaBaseCodeDisplay` ArmorStand **отключены** (clear/hide сохранены).
- Core damage **не** перестраивает блоки базы; фонари на stone brick + chain; `UPDATE_SUPPRESS_DROPS`.

### Внешний browser overlay
- **Режим:** `TIKTOK_WINDOW_CHROMA`. Захват окна: `OPEN_OVERLAY_WINDOW.cmd` → `?background=chroma`, chroma **`#FF00FF`**. Native canvas 1080×1920 без CSS scale. Карточки показывают HP базы (`coreHp`/`coreMaxHp`). In-game HUD выключен.
- **Модули:** независимые `battle-overlay-module`, `top-five-countries-module`, `record-overlay-module` на `#overlay-workspace`. Edit: `?edit=1` — drag, fixed toolbar, статистика (сброс wins/points/record с confirm, только IDLE/BREAK). Layout v3 server JSON (`xRatio`/`yRatio`/`scale`/`visible`).
- **ТОП-5:** snapshot `topCountries` по `roundWins`; модуль 400px; число побед 30px + слово 19px; 0 побед → «ПОКА НЕТ ПОБЕД».
- **РЕКОРД:** persistent max fighters за один раунд (`ArenaScoreSavedData`); счётчик раунда в MatchManager; snapshot `fighterRoundRecord`.
- **Участники snapshot:** `CURRENT_ROUND_PARTICIPANTS` (`roundParticipants`); карточки = `!eliminated` (RESCUE остаётся); WAITING holder сразу; `pushNow` после gift/reset/win; `pushNowAfterElimination` при финальном knockout; порядок `joinOrder`.
- **Сетка:** CSS Grid `countries-1`…`countries-13-20` (1 кол. для 1–2 стран, иначе 2; LARGE/MEDIUM/COMPACT/ULTRA_COMPACT); без scrollbar / `transform: scale`.
- **Почему не HTTP:** LIVE Studio отклоняет `http://127.0.0.1:...` («Введите корректную ссылку»). Нужен HTTPS + доверенный локальный сертификат Windows.
- **Одноразовая настройка:** `SETUP_LOCAL_OVERLAY_HTTPS.cmd` (UAC) → Root CA в `LocalMachine\Root`, **server cert в `Cert:\CurrentUser\My`** с SAN `localhost` + `arena-overlay.test` + `127.0.0.1`. Runtime через SunMSCAPI **`Windows-MY`**. Hosts для основного URL **не требуется** (`customHostsRequired=false`). Legacy PKCS12/DPAPI опциональны. Скрипт: UTF-8 BOM, `-ValidateOnly` / `-VerifyInstalled`, Gradle `validatePowerShellScripts` + `verifyWindowsMySsl`.
- **Overlay HTTPS:** `127.0.0.1:8766` (`HttpsServer` / `ArenaOverlayHttpServer`) — whitelist: `/overlay/**`, `/arena/health`, `/arena/overlay-state`, `/api/arena/state`. Gift/chat/S2E **нет** (404).
- **StreamToEarn HTTP:** отдельно `127.0.0.1:8765` — только health + gift/chat при `s2e_http_enabled` + token.
- Lifecycle: start на `SERVER_STARTED`, stop на `SERVER_STOPPING`; повторный start → `alreadyRunning`; без keystore → понятная ошибка + SETUP cmd.
- Config: `overlay_http_enabled`, `overlay_http_bind=127.0.0.1`, `overlay_http_port=8766`, `overlay_https_enabled=true`, `overlay_https_hostname=localhost`, `overlay_poll_interval_ms=750`. Публичный tunnel path принудительно выключен.
- UX: `START_ARENA.cmd` (проверяет keystore/hosts), `OPEN_OVERLAY.cmd`, `OVERLAY_README_RU.txt`, `tools/overlay-https/`.
- Команды: `/arena_overlay_status` (`LOCAL_HTTPS_BROWSER`, cert/hosts флаги, `overlayParticipantSource`/`overlayDisplayedCountries`/`overlayCardSizeMode`/…, без секретов).
- Unit-тесты: HTTP whitelist + HTTPS keytool ephemeral + Windows-MY selection/legacy isolation + jar safety + reconnect/PublicUrl + `ArenaOverlayLayoutAndSnapshotTest` (1/6/20 стран, reset, order).
- Сценарий: `/arena_test_scenario overlay_tiktok_test`.

## 16. Проверенные функции

Проверено в Minecraft (по тестам пользователя):

- humanoid PlayerModel;
- упрощённый режим одного класса «Боец» (SCOUT-профиль);
- Steve fallback при отсутствии скина;
- визуальное оружие в руках;
- клиентские частицы;
- отсутствие волчьего прыжка;
- движение рук / ванильный удар оружием;
- флаги стран над головой (PNG 128×80 из overlay SVG, world-space billboard);
- HP-полоски под флагом;
- крупные world-space маркеры баз (`ArenaBaseMarkerRenderer`, HD 256×160) — **подтверждено в Minecraft** (видны с большого расстояния);
- фонари больше не выпадают при атаке ядра — **подтверждено в Minecraft**;
- **`/arena_test_scenario full_country_lifecycle` — полностью проверен в Minecraft:** `FULL COUNTRY LIFECYCLE: PASS`; стадии 1–7 PASS (PROTECTED, LAST DEFENDER, CORE ATTACK, RESCUE, ELIMINATION, ROUND CONTINUES, WINNER); `overlayRescueSeen=true`; `overlayEliminatedSeen=true`; победитель RU; UA и KZ выбыли; автозавершение PASS без тройного spam очереди UA.
- **TikTok overlay PNG flags — подтверждено в TikTok LIVE Studio**: мерцание SVG/CEF полностью исчезло; текст/HP/статусы/PNG-флаги стабильны; прозрачный browser source 1080×1920 работает (исторически через tunnel; текущий бесплатный путь — локальный `127.0.0.1:8766`).
- **Полный массовый бой 1000×1000 — подтверждён вручную в Minecraft** (`/arena_round_reset` + `/arena_gift ru 1000` + `/arena_gift ua 1000`): волны резерва, выход с баз, rally → melee, приемлемая производительность, атака ядер, rescue/elimination, завершение с победителем.

Сборка `gradlew.bat clean build` доведена до **BUILD SUCCESSFUL** (30.07, false FAILED / runId result JSON fix).

---

## 17. Отложено

Не предлагать без прямого запроса пользователя:

- полноценные скины четырёх фракций / формации как ближайший этап;
- дополнительные массовые VFX сверх уже усиленных ульт ELITE/CHAMPION/TITAN;
- StreamToEarn / TikTok API / внешний HTTP-WebSocket вход подарков как ближайший этап.

---

## 18. Текущий фокус

Локальный HTTPS overlay для TikTok LIVE Studio (захват окна + chroma `#FF00FF`). In-game Arena HUD отключён. Адаптивная сетка 1–20 стран + мгновенный WAITING holder. Перемещаемые модули боя и ТОП-5 (`?edit=1`). Названия стран над флагами баз в мире.

---

## 19. Последние изменения

~15–20 последних этапов по коду. Без простыней Java.

82. **Remove overlay team-codes hint (31.07)** — убран блок «КОДЫ КОМАНД — НАПИШИ В ЧАТ» из TikTok overlay (HTML/CSS/JS) и поля `teamJoinCodes`/`teamJoinHint` из snapshot. Вступление в команду чатом без изменений. Тест обновлён. `clean build` SUCCESS; Minecraft не запускался.

81. **Core HP regen in BATTLE (31.07)** — +5 HP / 5с после 15с без урона; только активные не-eliminated ядра с HP&gt;0; кап max HP; timestamps в `ArenaCoreState`; тик только из `tickBattle`; диагностика в `/arena_core_combat_status`. Тесты `ArenaCoreRegenTest`. `clean build` SUCCESS; Minecraft не запускался.

80. **WAITING field limit 25 (31.07)** — во время WAITING на поле максимум 25 живых спортсменов ожидающей команды; волны с тем же `reserveReleaseBatch`/interval; формула с `waitingRemainingSlots`; сверх лимита — в резерве; подарки продолжают наполнять резерв. Старт BATTLE: 25 остаются, лимит снимается. Счётчик живых не переносится (reset/clear). Тесты `ArenaWaitingFieldLimitTest`. `clean build` SUCCESS; Minecraft не запускался.

79. **Team join without ! (31.07)** — основной чат-формат `ru` / `команда ru` (trim, lowercase, точное совпадение); `!ru` только legacy. Уведомление `@user вступил в команду …`; диагностика lastTeamJoin*; overlay подсказка кодов без `!`. Gift pipeline без изменений. Тесты `ArenaTeamJoinParserTest`. `clean build` SUCCESS; Minecraft не запускался.

78. **Stable fighter flags rollback (31.07)** — откат визуального gate FPS-оптимизации: флаги снова каждый frame без LOD/budget; убран custom `shouldRender` distance cull; billboard `new Quaternionf(camera)` каждый кадр. Particle budget/config/команды оставлены. Причина мерцания: NEAR-only overhead + max 20 nameplates. `clean build` SUCCESS; Minecraft не запускался.

77. **CLIENT FPS OPTIMIZATION (31.07)** — клиентский LOD/culling бойцов без смены геймплея. `ArenaClientPerfConfig` → `config/arena_of_nations-client.properties` (render 128, shadows default off, adaptive). `ArenaFighterRenderer.shouldRender` + distance cull (squared); тени/overhead по NEAR/MID/FAR; immutable country→skin cache; entityId→country cache; `ArenaFighterVisualEffects` без per-tick `getEntitiesOfClass`, particle budget 20/48. Команды `/arena_client_perf`, `/arena_client_config_reload`. Тесты `ArenaClientPerfOptimizationTest`. `clean build` SUCCESS; Minecraft не запускался.

76. **Live reserveReleaseBatch 1–100 (31.07)** — edit overlay секция РЕЗЕРВ (`?edit=1`): −/+ / поле / пресеты / ПРИМЕНИТЬ СЕЙЧАС. Runtime `ArenaReserveRuntimeSettings` (AtomicInteger) + persist `reserve_wave_size`. API `GET/POST /overlay/api/runtime/reserve-settings`; `/arena_reserve_batch`; диагностика status-команд; snapshot `runtimeSettings.reserveReleaseBatch`. Scheduler читает batch на каждой волне; apply не стартует волну и не сбрасывает таймер; `activeFightersLimit`/`interval` не менялись. Тесты `ArenaReserveReleaseBatchTest`. `clean build` SUCCESS; Minecraft не запускался.

75. **Fighter round record + stats reset (31.07)** — модуль `record-overlay-module` (160×100, золотой круглый флаг); persistent `fighterRoundRecord` в `ArenaScoreSavedData` (`>` only); `fightersSentThisRound` в MatchManager (acceptFighter / gift pipeline); сброс counters на beginBreak/reset. Edit: секция СТАТИСТИКА + confirm dialog; POST `/overlay/api/stats/reset-*` (IDLE/BREAK only, 409 иначе). Layout v3 + `record` module. Команды `/arena_fighter_record_status|reset`. Тесты `ArenaFighterRecordAndStatsResetTest`. `clean build` SUCCESS; Minecraft не запускался.

74. **Overlay server layout + top5 sizes (31.07)** — layout в `config/arena_of_nations_overlay_layout.json` (xRatio/yRatio); API `GET/POST /overlay/api/layout`, `POST .../reset`; миграция из localStorage; workspace `#overlay-workspace` без clip; drag по всей странице + auto-scroll в edit; ТОП-5 width 400px, wins 30px/19px; toolbar `position:fixed`. Диагностика SERVER_CONFIG. Тесты `ArenaOverlayLayoutConfigTest` + обновлённые overlay-тесты. `clean build` SUCCESS; Minecraft не запускался.

73. **Overlay drag + ТОП-5 roundWins (31.07)** — два независимых модуля (`battle-overlay-module`, `top-five-countries-module`) с `left/top`; `?edit=1` — drag за ручку (Pointer Events), СОХРАНИТЬ/СБРОСИТЬ, скрытие БОЙ/ТОП-5; позиции и visibility в `localStorage`. Snapshot: `topCountries[]` (rank, countryId, displayName, flagUrl, roundWins, scorePoints). `ArenaScoreSavedData.roundWins` отдельно от очков; +1 при `awardBattleWin`/`awardHold`, ничья без начисления; `pushNow` после записи. Склонение `ArenaRoundWinsGrammar`. Диагностика `/arena_overlay_status`. Тест `ArenaOverlayTopFiveAndDragTest`. `clean build` SUCCESS; Minecraft не запускался.

72. **Release v1.0-stream-tested (31.07)** — зафиксирован стабильный пакет `releases/v1.0-stream-tested` после реального TikTok LIVE-стрима. `clean build` SUCCESS; jar `arena_of_nations-1.0.0.jar`; игровой код при упаковке не менялся; секреты/сертификаты не копировались.

71. **Overlay hide eliminated cards (31.07)** — browser `countries` = `currentRound && !eliminated` (RESCUE остаётся). `activeCountryCount` = число карточек; grid densеity по новому N. `pushNowAfterElimination` сразу после финального knockout. JS-guard + banner при исчезновении id. Диагностика `overlayRoundParticipants`/`overlayEliminatedCountries`/`overlayDisplayedCountries`/`overlayLastRemovedCountry`. Тест `ArenaOverlayEliminatedCardFilterTest`. `clean build` SUCCESS; Minecraft не запускался.

70. **Fix invisible base country name (31.07)** — название больше не в локальной матрице флага (`-FLAG_HALF_H - gap`); отдельный world-billboard: `labelY = flagCenterY + FLAG_HALF_H + 0.85`, `scale(-0.045)`, `Font.DisplayMode.SEE_THROUGH`, `FULL_BRIGHT`. Диагностика draw на клиенте `/arena_base_markers status`. `ArenaBaseMarkerLayout` + тесты render-math. `clean build` SUCCESS; Minecraft не запускался.

69. **Base country label lifecycle (30.07)** — названия над флагами только у `CURRENT_ROUND_PARTICIPANTS` (!eliminated, slot>=0); RESCUE сохраняет, ELIMINATION/reset скрывает вместе с флагом. `ArenaRoundHudSync.pushNow` после gift/reset для мгновенного WAITING. Диагностика `baseCountryLabels*` в `/arena_base_markers` и `/arena_visual_status`. Тест `ArenaBaseCountryLabelLifecycleTest` (10 PASS). `clean build` SUCCESS; Minecraft не запускался.

68. **Base country name above flag (30.07)** — `ArenaBaseMarkerRenderer`: человекочитаемое `Country.getDisplayName()` рисуется **над** флагом базы (client billboard, без TextDisplay). Под флагом остаются ЯДРО/полоска/статус. Legacy server TextDisplay по-прежнему disabled + cleanup. Тест `ArenaBaseMarkerNameLabelTest` (3 PASS). `clean build` SUCCESS; Minecraft не запускался.

67. **Adaptive overlay grid + WAITING first country (30.07)** — JS больше не кладёт первые 10 стран в один левый столбец (`slice(0,10)`); CSS Grid `countries-1`…`countries-13-20` (1 кол. для 1–2, иначе 2; LARGE/MEDIUM/COMPACT/ULTRA_COMPACT). Snapshot: только `CURRENT_ROUND_PARTICIPANTS` + `joinOrder`; WAITING holder сразу в `countries`; overlay-only ЩИТ в WAITING; `pushNow` после gift/reset. Диагностика в `/arena_overlay_status` и `?preview=1`. Тесты `ArenaOverlayLayoutAndSnapshotTest` (12 PASS). `clean build` SUCCESS; Minecraft не запускался.

66. **Magenta chroma + larger HP cards (30.07)** — chroma `#00FF00` → **`#FF00FF`** (зелёный key вырезал HP/панели). Убраны CSS `transform: scale` / fit-scale; native 1080×1920; UI ~+40%; карточки показывают БАЗА `coreHp/coreMaxHp` + полоску (зелёный/жёлтый/красный); щит не скрывает HP. Snapshot: `coreProtected`/`coreVulnerable`/`rescueRemaining`. `OPEN_OVERLAY_WINDOW` + `--force-device-scale-factor=1`. README без OBS. `clean build` SUCCESS; Minecraft не запускался.

65. **Window chroma + remove in-game HUD (30.07)** — режимы `?background=transparent|chroma`; chroma тогда был `#00FF00`; `OPEN_OVERLAY_WINDOW.cmd`; `ArenaRoundHudRenderer` без HudRenderCallback. `clean build` SUCCESS.

64. **Primary URL localhost (proxy-safe) (30.07)** — основной URL `https://localhost:8766/overlay/tiktok`; Windows-MY SAN localhost+legacy+127.0.0.1.

63. **Windows-MY HTTPS (no DPAPI) (30.07)** — runtime через SunMSCAPI `Windows-MY`, без обязательного PKCS12/DPAPI.

1. **Humanoid foundation** — wide PlayerModel, Steve fallback, visual weapons, tier client VFX, flags + overhead HP.
2. **Melee AI** — свои goals без wolf leap; `ArenaFighterMeleeAttackGoal`; windup **3** тика; sticky target; reach 2.35/2.95; **без** `Goal.Flag.MOVE`.
3. **Client round HUD** — S2C `round_hud` + `ArenaRoundHudRenderer`; GUI flag blit full 64×40→20×12.
4. **Positioning experiment** — удалён; прямой chase + `getCoreAttackPosition`.
5. **Legacy abilities/VFX** — ELITE/CHAMPION/TITAN ability-код и VFX-pass сохранены в проекте как legacy-контекст.
6. **Economy + core protection** — tiebreak core HP%→damage; `isCoreProtected` на fight level; фикс fight-level tick/spam.
7. **Melee test placement** — `ArenaTestMeleePlacement` (линии X=±4; density ±6..±10). Реализовано.
8. **Humanoid arm swing** — `ArenaFighterEntity.aiStep()` → `updateSwingTime()` (Wolf не крутит swing; humanoid рука машет).
9. **BossBar off** — `bossBarEnabled=false`; `/arena_hud` = client HUD; `/arena_hud bossbar` = отладка.
10. **Death feedback** — через `ArenaCombatSpectacle.onFighterDeath` (усилено в п.37); пустой `dropAllDeathLoot`, `shouldDropExperience=false`.
11. **Elimination UI** — chat/actionbar «✖ … выбыла»; HUD **ВЫБЫЛА** только при `eliminated` (не при coreHP≤0).
12. **Rescue variant C** — countdown только при 0 HP + 0 бойцов; **ЯДРО СБИТО** / **СПАСЕНИЕ Ns**; gift +50% max HP + reset; боец/heal reset; expiry → clear fighters/reserve.
13. **HUD badges** — крупный таймер; цветные плашки ЩИТ / ЯДРО СБИТО / СПАСЕНИЕ / ВЫБЫЛА (`ArenaRoundHudRenderer`).
14. **Single-class gameplay mode** — один класс «Боец» (SCOUT-профиль); `/arena_gift coins` = `coins` бойцов; `/arena_spawn` только для одного класса.
15. **Gameplay simplification safety** — tier-based abilities/scaling/swarm сняты с live-боевого пути; reserve/волны/лимиты и rescue C сохранены.
16. **PROJECT_STATUS sync 28.07.2026** — статус обновлён по фактическому коду после упрощения экономики/класса.
17. **Attack cadence boost** — для класса «Боец» (`SCOUT`) `attackCooldownTicks` снижен до **10**; windup/reach/damage/HP/speed/gift/reserve/cores/HUD/VFX не менялись.
18. **Tempo+reserve boost** — `SCOUT` cooldown снижен до **7**; `reserve_wave_size` = **10**; `reserve_wave_interval_ticks` = **40**. Gift 1:1, windup/reach/ядра/rescue/HUD/S2E без изменений.
19. **Reserve wave release fix** — в `BATTLE` выпуск резерва теперь идёт пачками `min(reserve_wave_size, queueSize)` без капа живых на поле; gift всегда уходит в резерв. `run/config` обновлён: `reserve_wave_size=10`, `reserve_wave_interval_ticks=40` (для применения в рантайме: `/arena_config_reload`).
20. **Test scenarios single-class** — `melee_contact`, `melee_density`, `core_unprotected_attack`, `core_protection` адаптированы под один класс «Боец» (SCOUT): прямой спавн на поле через `spawnTestFightersOnField`, без tier и без капа max на поле; `ArenaTestMeleePlacement.findFighters` по стране. Проверено сборкой; in-game — команды из handoff.
21. **20-country dynamic round** — `Country` → 20 string id; slot-based cores/spawns; компактный HUD до 20 строк; сценарии `twenty_countries`, `twenty_countries_mass`, `countries_joining`. Scores/NBT по id. Проверено сборкой.
22. **Layout/spawn/HUD/flags fix (28.07)** — strict `ArenaCountryLayoutValidator` (min 8 safe spawns + navigation path); `ArenaLayoutPathfinder`; spawn grid 2×5 на платформе 11×6; **Base v4** 13×9×10; **HUD v3** 4×5 при 20 странах; флаги IL/AL/KG/TJ/MD/KZ/UZ/TM перерисованы; `tools/generated/flags_contact_sheet.png`; сценарии `layout_spawn_test`, `hud_twenty_states`. Проверено **BUILD SUCCESSFUL**; in-game — rebuild arena + validate + HUD/flags visual check.
23. **External overlay + walkable field split (28.07)** — убран физический inner border (`ArenaBuilder.stepInnerRing` skip), добавлен `COMBAT_WALKABLE_RADIUS` отдельно от `CENTER_PATTERN_RADIUS`, валидатор/спавн проверяют walkable-радиус и path к нескольким центральным целям. Добавлен thread-safe overlay snapshot (`ArenaOverlayStateService`) и local-only HTTP overlay (`/overlay`, `/api/arena/state`) на `127.0.0.1`; HTML/CSS/JS overlay + 20 SVG флагов (`flag-icons`, лицензия внутри assets). Добавлены HUD modes `external|minimal|full|off` (default external) и overlay команды статуса/restart. Подавлен массовый spam объявлений уязвимости вышек в 20-country тест-сценариях. Проверено **BUILD SUCCESSFUL**; in-game/OBS требуется ручная проверка.
24. **Core combat transition fix (28.07)** — **причина:** старая `coreAttackPosition` на радиусе **60** при ядре на **67**; боец останавливался у approach (≤1.5 блока), но оставался ~7 блоков от ядра при `CORE_ATTACK_RANGE=3.5`. **Исправление:** отдельные `visualCorePosition` / `coreDamagePosition` / `coreApproachPosition` (3 блока inward по углу слота); `resolveCoreApproachPosition()` ищет безопасную точку; `ensureNavigatingToCore` после arrival продолжает navigation к `corePos`; dead target очищается в `FighterTargeting` + `ServerLivingEntityEvents.AFTER_DEATH`; инвалидация `cachedLivingCounts` при смерти. Расширен `/arena_core_combat_status`. Сценарий `core_after_last_defender`. Проверено **BUILD SUCCESSFUL**; in-game — ручной тест.
25. **Path validator fix (28.07)** — probe с `setNoAi(false)`; path успешен если достижима любая из целей center±4; различаются `no path` / `entity failed` / `endpoint outside`. Проверено сборкой; in-game validate vs реальный выход бойцов.
26. **Overlay snapshot fix (28.07)** — **причина пустого snapshot:** не было `pushNow()` после setup/spawn; tick мог публиковать пустое состояние до регистрации участников. Добавлены `ArenaOverlayStateService.pushNow`, вызов из `ArenaRoundHudSync.pushNow`, test-сценариев, `/arena_overlay_dump`; `app.js` — empty states + core HP в строке. Сценарии `overlay_twenty`, `twenty_countries_mass` с push. Проверено **BUILD SUCCESSFUL**; браузер/API — ручная проверка.
27. **Fighter flags PNG + renderer (28.07)** — единый источник: `overlay/flags/*.svg` (flag-icons) → offline `tools/rasterize_flags.mjs` (@resvg/resvg-js + sharp) → `textures/gui/flags/<id>.png` **128×80**; contact sheet `tools/generated/fighter_flags_contact_sheet.png`. World renderer: half 16×10, scale 0.032, дистанции флаг **40** блоков / HP **18** блоков, тёмная рамка. Сценарий `fighter_flags_test`. Проверено генерацией PNG + **BUILD SUCCESSFUL**; in-game — визуальная проверка.
28. **Base/core TextDisplay labels (28.07)** — `ArenaCoreDisplayManager`: один TextDisplay на слот (NBT `Entity.load` из-за private API 1.21); строки «СТРАНА [CODE] / ЯДРО hp/max / ЩИТ|УЯЗВИМА|…»; scale 2.4, viewRange 128, ~8 блоков над ядром inward. Очистка через `CORE_DISPLAY_TAG` при `/arena_setup_clear`. Layout status: visualCore, approach, displayExists. Сценарий `base_display_test`. Проверено **BUILD SUCCESSFUL**; in-game — читаемость с полёта.
29. **TikTok overlay + HD base markers (28.07)** — вертикальный overlay `/overlay/tiktok` (1080×1920, transparent, safe CSS vars, 2 колонки ×10, event banners, `?scale=` / `?preview=1`). HD PNG `flags_hd/` 256×160 + contact sheet. Client `ArenaBaseMarkerRenderer`: флаг 5×3, название, HP, статус; дистанция 100–120; данные из расширенного `ArenaHudSnapshot` (страны всегда + arenaCenter). Legacy TextDisplay/ArmorStand labels отключены. Команды `/arena_base_markers`, сценарии `base_display_test` (8 баз), `overlay_tiktok_test`. HUD EXTERNAL показывает только таймер/счётчик/rescue. Проверено **BUILD SUCCESSFUL**; Minecraft/OBS/браузер — ручная проверка.
30. **Base marker black-quad + core lantern drop fix (28.07)** — **Markers:** чёрный прямоугольник из‑за тёмной рамки на z=+0.02 *перед* флагом (z=0) после billboard; текст/HP не читались. Исправлено: billboard `scale(-1,-1,1)`, рамка за флагом (z=-0.02), `RenderType.entityCutoutNoCull` + FULL_BRIGHT, UV как у fighter overhead, двусторонний quad, Font отдельно (NORMAL + shadow). Status: first5 diag entries. **Cores:** каждый `damageFromFighter` вызывал `refreshVisual` → полный `clearFortressVolume`+rebuild → фонари падали. Урон/heal больше **не** меняют блоки; visual только activate/eliminate/rescue restore/reset. Фонари на постоянных stone brick + chain; `UPDATE_SUPPRESS_DROPS` в CoreBuilder/RegionClear/ArenaBuilder. Сценарии `base_marker_flags_test`, `core_structure_integrity`. Проверено **BUILD SUCCESSFUL**; in-game — markers + integrity.
31. **full_country_lifecycle E2E (28.07)** — `/arena_test_scenario full_country_lifecycle` (RU/UA/KZ), 7 стадий, реальные melee/core/rescue/elimination/winner. **Подтверждено в Minecraft: `FULL COUNTRY LIFECYCLE: PASS`**, все 7 стадий PASS, `overlayRescueSeen=true`, `overlayEliminatedSeen=true`.
32. **full_country_lifecycle STAGE3 RU missing fix (28.07)** — KZ `arena_ai_frozen` + UUID RU-атакующего; targeting не снимает freeze. Вошло в подтверждённый PASS прогон.
33. **full_country_lifecycle STAGE7 PASS latch (28.07)** — observed elimination до `beginBreak/clearAll`; one-shot gift coins=1. **Подтверждено в Minecraft:** авто `PASS`, без тройного spam очереди UA.
34. **TikTok preview canvas fit (28.07)** — логический холст **1080×1920**; OBS transparent; `?preview=1` равномерный fit-scale. **Подтверждено в браузере** (9:16, resize, checkerboard, guides, badge).
35. **TikTok cards readability pass (29.07)** — отдельные полупрозрачные карточки; 2 строки; акценты статусов; крупнее header/таймер; event banners. Canvas/API/poll не менялись.
36. **TikTok card status layout fix (29.07)** — grid `FLAG|CODE|БОЙ|РЕЗ|STATUS` с фиксированной зоной статуса ~96–105px без ellipsis; `БОЙ N` / `РЕЗ N` с пробелом; rescue badge `Nс`. Проверено сборкой; browser/OBS — ручная проверка.
37. **Combat spectacle pass (29.07)** — `ArenaCombatSpectacle`: усиленные swing/hit/death/core particles+sounds для стрим-дистанции; клиент SCOUT получает attack VFX (`CRIT` + cue к цели). Урон/AI/cooldown не трогались. Проверено **BUILD SUCCESSFUL**; in-game — duel / `melee_contact` / удар по ядру.
38. **Opposite-base march fix (29.07)** — living search 80 < дистанция противоположных spawn; добавлен `rallyTowardEnemyFront`.
39. **TikTok overlay flag flicker fix (29.07)** — стабильный DOM (`cardById`).
40. **TikTok overlay PNG flags (29.07)** — PNG 256×160 через `background-image`; мерцание CEF устранено (**TikTok LIVE Studio PASS**).
41. **Mass duel 1000×1000 manual PASS (29.07)** — gift RU/UA 1000 подтверждён в Minecraft.
42. **StreamToEarn audit stage 1 (29.07)** — карта пути S2E→gift; `/arena_s2e_status`; `s2e_local_gift`. Реальный эфир не подключался.
43. **Base flag lifecycle + shared skin/cape + fighter flag flicker (29.07)** — `ArenaBaseFlagVisibility`; shared skin; overhead flag z-fight/hysteresis fix. In-game: base flags/lifecycle ок; **плащ был кривой/без цвета** → п.44.
44. **Cape body-attach + tint fix (29.07)** — **геометрия:** root `PlayerModel.cloak` у pivot модели (ноги) без body transform / без vanilla Y-180 → плоскость сквозь торс. **Цвет:** надёжный tint через явный `VertexConsumer.setColor(0–255)` на quads + white `cape_mask.png` (не ModelPart ARGB + white_pixel на cloak UV). Плащ: `body.translateAndRotate` + offset за спиной + лёгкий X hang; layers=1; vanilla cloak hidden. `/arena_visual_status` + client nearest/last cape diag. Проверено **BUILD SUCCESSFUL**; in-game — `/arena_test_scenario melee_contact` (RU/UA цвета, поворот с телом).
45. **Base flag pathing/rebuild pass (30.07)** — проверен активный путь больших флагов: только `ArenaBaseMarkerRenderer` (client billboard), не entity/blocks; расширен `/arena_visual_status` (`baseFlagExpected/baseFlagEntityExists/baseFlagVisible/baseFlagUUID/duplicates`, spawn/floor/exit/rally). Малые флаги бойцов — один render path сохранён. Исправлена «траншея» у выхода баз: в `ArenaCoreBuilder` убран slab-бордюр (полные блоки) + добавлен плоский коридор 5 блоков шириной с 3-блочным клиренсом к центру. Добавлен safe rebuild `/arena_rebuild confirm` (стоп раунда, очистка только footprint арены, запуск новой сборки без сброса очков/S2E). Добавлены сценарии `base_flag_lifecycle` и `base_exit_pathing`. Build version повышен до **2**. Проверено **BUILD SUCCESSFUL**; Minecraft-тесты требуются вручную.
46. **Fortress trench root-fix (30.07)** — найдена первопричина: `clearFortressVolume` очищал пол вокруг крепости, но часть площади не заполнялась обратно, из-за чего появлялся кольцевой провал/«траншея». Добавлен `refillFortressApron` (плоский `SMOOTH_STONE` на уровне footing по периметру крепости перед сборкой башен/ворот). Проверено **BUILD SUCCESSFUL**; нужно вручную подтвердить в Minecraft после `/arena_rebuild confirm`.
47. **Shared medieval skin + cape removal + spear visual (30.07)** — выбран единый resource id `arena_of_nations:textures/entity/fighter/medieval_soldier.png` (wide Steve/4px arms). Автоскачивание оригинального PNG с PlanetMinecraft заблокировано Cloudflare вне интерактивной сессии: в проект не добавлен заменяющий PNG, требуется ручное размещение оригинала по пути `src/main/resources/assets/arena_of_nations/textures/entity/fighter/medieval_soldier.png`. Добавлен `CREDITS.md` (Mullraugh + source + permission). Полностью удалён активный путь плаща: слой, маска и cape-диагностика цвета. Визуальный `trident` заменён на `arena_of_nations:medieval_spear` (custom item + item model), привязка в правой руке через `ArenaFighterHeldItemLayer`; механика боя не менялась. Проверено **BUILD SUCCESSFUL**; требуется ручная проверка в Minecraft (скин/UV/копьё в руке/FPS).
48. **Steve fallback root-cause + spear render-path hardening (30.07)** — причина Steve в Minecraft: файла `textures/entity/fighter/medieval_soldier.png` нет в ресурсах, поэтому до фикса рендер молча уходил в fallback. Исправлено: активный renderer (`ArenaFighterRenderer#getTextureLocation`) теперь всегда использует `ArenaFighterVisuals.SHARED_SKIN`; `DefaultPlayerSkin/SkinManager` не применяются; добавлен одноразовый ERROR-лог при отсутствии PNG и расширена `/arena_visual_status_client` (renderer/model/active texture/existence/dimensions/weapon paths). Причина пустой руки после удаления трезубца: item-model копья была нестабильна для runtime-пути (3D json без подтверждённого `layer0`-текстурного ресурса). Исправлено: `item/medieval_spear` + `textures/item/medieval_spear.png` (16x16 PNG, в jar) + `weapon_mode=ITEM_STACK`, `itemInHandLayerRegistered=true`, `active_weapon_render_paths=1`. Проверено `clean build` и проверкой jar: `models/item/medieval_spear.json` и `textures/item/medieval_spear.png` присутствуют; `medieval_soldier.png` в jar отсутствует до ручного добавления оригинального PNG.
49. **Medieval glaive visual rewrite (30.07)** — старое оружие выглядело как толстая доска/кубы: активной была плоская `item/handheld` 16×16 текстура + избыточный layer scale 1.35. Заменено на thin low-poly 3D JSON (7 cuboids: тонкое древко 0.5u, кожаная обмотка, компактная втулка, листовидный клинок из 3 сегментов + ridge) и новую texture atlas **32×32**. Display scale 0.92, layer angle -20°, layer scale 1.05 (≈70° к полу). Диагностика: `weaponVisualType=MEDIEVAL_GLAIVE`, `weaponRenderPaths=1`, `tridentRenderPaths=0`, `capeRenderLayers=0`. Механика атаки не менялась. Проверено `clean build` + jar (model+PNG decode 32×32); in-game — ручная проверка формы/угла.
50. **Medieval soldier skin installed (30.07)** — причина purple/black бойцов: `SHARED_SKIN` указывал на `medieval_soldier.png`, файла не было в resources/jar. Добавлен валидный PNG 64×64 (подпись `89 50 4E 47…`, атлас Steve, кольчуга + красный сюрко; `CREDITS.md` — Mullraugh). Путь: `src/main/resources/assets/arena_of_nations/textures/entity/fighter/medieval_soldier.png`. Код рендера не менялся. Проверено `clean build` (**BUILD SUCCESSFUL**) + jar entry `assets/.../medieval_soldier.png` size=1863; in-game — перезапуск клиента / F3+T и визуальная проверка скинов + глефы.
51. **Full project audit + safety fixes (30.07)** — первичный аудит ~11 мин (таймер), ~97 Java-файлов + fabric/Gradle/ресурсы/jar. **CRITICAL исправлены:** (1) CME в `ArenaCoreCombatManager.tickWindups` при kill ядра mid-windup — итерация по копии entrySet; (2) ложный победитель при одновременном expiry нескольких rescue — сначала mark eliminated для всех, потом `onCountryEliminated`. **HIGH:** освобождение base slots на eliminate/break/next-round; S2E/viewer gifts drain до rescue tick (`processQueuedEvents`); отказ JSON-полей с `|||` (injection coins). **MEDIUM:** sync queueSize/clear; stop HTTP перед clear queue; stop частично стартовавшего HttpServer. Не трогались: арена/базы/скины/копья/флаги/плащи/баланс. Overlay desktop DOM churn и `s2e_http_port` при overlay=on — оставлены. Автотестов Gradle нет. Проверено `clean build` (**BUILD SUCCESSFUL**); Minecraft не запускался.
52. **BREAK multi-country queue promote (30.07)** — после ничьей/победы подарки в BREAK шли в `nextRoundQueue` (UA+RU ок в чате), но `tickBreak` поднимал только первую страну в WAITING/резерв; остальные навсегда оставались в очереди → HUD «Стран: 1». Исправлено: `promoteQueuedCountriesAfterBreak` после старта первой страны прогоняет остаток очереди через `handleGiftWhileWaiting` / `handleGiftWhileBattle` (вторая страна сразу вступает и запускает бой). Проверено сборкой; in-game — gift UA+RU во время перерыва.
53. **15-min full audit + cleanup (30.07)** — аудит 15:48:19→16:03:19 (ровно 900с). CRITICAL/HIGH новых lifecycle-багов не найдено (предыдущие CME/false-winner/BREAK promote держатся). Исправлено: invalidate living-count cache после `discard`; cap `countryByViewer` (5000 LRU) + удалён мёртвый `nameByViewer`; сценарий `/arena_test_scenario break_multi_country_queue` (UA+RU / RU+UA / repeat / 3 страны / single / reset mid-BREAK); lang `en_us`/`ru_ru`; удалены Example mixins, `RuScoutModel`+`ru_scout.png`, cape helpers в palette, пустой client mixins. Не трогались арена/скины/копья/флаги/плащи/баланс/TikTok design. `clean build` **BUILD SUCCESSFUL**; Gradle tests NO-SOURCE; Minecraft не запускался.
54. **Stable overlay HTTP split (30.07)** — overlay-only HTTP `127.0.0.1:8766` (`ArenaOverlayHttpServer`, whitelist) отделён от S2E `8765`; общие handlers `ArenaOverlayHttpIO`; config `overlay_http_*` + optional `overlay_public_*` (default off); TikTok JS reconnect/backoff/AbortController + lastSuccessfulData; `START_ARENA.cmd`. Unit-тесты PublicUrl/HttpServer/ReconnectScript — **10 PASS**.
55. **Remove paid overlay path (30.07)** — удалён мастер Named Cloudflare Tunnel (`ArenaOverlaySetup.*`); убраны проверки службы cloudflared из `START_ARENA.cmd`; основной путь — бесплатный локальный URL `http://127.0.0.1:8766/overlay/tiktok` + `CopyLocalOverlayUrl.cmd` / README без домена. Java/ресурсы не менялись (сборка не требовалась).
56. **TikTok compact stream HUD (30.07)** — переработан `overlay/tiktok`: прозрачный 9:16 HUD; шапка «фаза · ДО КОНЦА mm:ss · N стран»; карточки одной строкой (флаг/код/бойцы/резерв/статус); HP-полоски убраны; до 20 стран (10+10) без перекрытия центра. Проверено `clean build`.
57. **OBS blank overlay fix (30.07)** — авто-fit 1080×1920 в любой размер Browser Source; HTML/CSS/JS `Cache-Control: no-store` (флаги PNG кэшируются); стартовая надпись «Загрузка…»; инструкция OBS в `tools/overlay-setup`. Проверено сборкой.
58. **Final local browser overlay (30.07)** — стабильный LOCAL_BROWSER путь: порт **8766** whitelist-only; S2E на **8765**; immutable snapshot + empty IDLE JSON; health/`/arena_overlay_status` без секретов; poll **750ms**, reconnect 1→2→4→8→max10s, RECONNECTING без очистки DOM; `START_ARENA.cmd` / `OPEN_OVERLAY.cmd` / `OVERLAY_README_RU.txt`; Cloudflare setup не используется. Проверено `clean build` + unit-тесты; Minecraft не запускался.
59. **Local HTTPS overlay for LIVE Studio (30.07)** — HTTP localhost отклонялся источником «Ссылка»; переход на `https://arena-overlay.test:8766/overlay/tiktok`: hosts + локальный Root CA/trust + PKCS12 в `%LOCALAPPDATA%`, `HttpsServer`, `SETUP_LOCAL_OVERLAY_HTTPS.cmd`, обновлены START/OPEN/README/status/config. Приватный ключ не в Git/jar. Проверено `clean build` + unit-тесты; Minecraft не запускался.
60. **Fix HTTPS setup ParserError (30.07)** — `Setup-LocalOverlayHttps.ps1` был UTF-8 **без BOM** → Windows PowerShell 5.1 ломал кириллицу и `-TextExtension` с `&` (8 parser errors). Переписан UTF-8 **с BOM**, `-ValidateOnly`, UAC self-elevate, hosts без regex-багов, Root/server через `New-SelfSignedCertificate`, Gradle task `validatePowerShellScripts`. Parser=0, ValidateOnly=SUCCESS; полный UAC setup требует ручного запуска. Minecraft не запускался.
61. **HTTPS setup observability (30.07)** — elevated-окно больше не закрывается мгновенно: `Read-Host` (кроме `-NoPause`/`-ValidateOnly`); parent ждёт UAC через `Start-Process -Wait`; лог `%LOCALAPPDATA%\ArenaOfNations\overlay-https\setup.log` + `setup-result.json` без секретов; финальные проверки hosts/DNS/Root/SAN/PKCS12+DPAPI; CMD показывает SUCCESS/FAILED и путь к логу. Parser=0, ValidateOnly=SUCCESS; MANUAL UAC TEST REQUIRED. Minecraft не запускался.
62. **Fix false FAILED after SUCCESS (30.07)** — код `-1073741510` (0xC000013A) после Enter в admin-окне больше не даёт ложный FAILED: итог по `setup-result.json` + `runId`, атомарная запись JSON, единственный `exit` в конце PS1; `Resolve-OverlayParentOutcome`; `-VerifyInstalled` + `VERIFY_LOCAL_OVERLAY_HTTPS.cmd`; `START_ARENA` через VerifyInstalled. Автотесты result-логики PASS; сертификаты не пересоздавались. Minecraft не запускался.

Заменено ранее:

- wolf/Blockbench renderer → humanoid PlayerModel;
- custom трёхфазная атака → vanilla swing + `updateSwingTime`;
- name tags → флаги + HP;
- BossBar как основной UI → client round HUD (BossBar debug-only);
- «ВЫБЫЛА при coreHP≤0» / «донат обязателен при живых бойцах» → variant C;
- per-country fighter skins в live-path → один shared skin + цветной плащ;
- base markers показывали eliminated как «ВЫБЫЛА» → скрытие после elimination;
- root PlayerModel.cloak как плащ → body-attached tinted cape quads.
- slab-бордюр/неровный выход у spawn zone → плоский полно-блочный коридор выхода от базы к центру;
- плоский толстый `item/handheld` spear sprite → thin low-poly medieval glaive (JSON cuboids + 32×32 atlas);
- отсутствующий `medieval_soldier.png` (purple missing texture) → установленный 64×64 shared skin в jar.

---

## Правила обновления этого файла

После **каждого** законченного задания агент обязан обновить этот документ (см. `.cursor/rules/project-status-memory.mdc`): факты из кода, не планы; не раздувать журнал; не трогать `STREAMTOEARN_SETUP.md`, если задача не про StreamToEarn.
