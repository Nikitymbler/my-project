# Arena of Nations — Project Status

Документ состояния разработки. Источник правды — **текущий код**, не переписка.
Дата снимка: 28 июля 2026.

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

- Внешняя интеграция **StreamToEarn отложена** как текущий фокус разработки.
- В коде ещё есть HTTP/S2E/viewer-команды и мост — они не удалены, но **не являются ближайшей задачей**.
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
- Спавн на поле идёт волнами в `BATTLE`: `reserve_wave_size` за тик волны (по умолчанию 10).

Параметры единственного класса (SCOUT-профиль из `ArenaFighterBalance`):

| Параметр | Значение |
|---|---:|
| HP | 10 |
| Урон | 2.5 |
| Speed | 0.36 |
| Attack cooldown | 7 |
| KB resist | 0.00 |
| Visual scale | 0.85 |
| Оружие (visual) | trident |

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
- Ожидаемые скины: `assets/arena_of_nations/textures/entity/fighter/<country>/<tier>.png` (HEAVY/HERO → `elite.png` / `champion.png`).
- Нет PNG → fallback `minecraft:textures/entity/player/wide/steve.png`.
- Резолв текстур кэшируется; кэш сбрасывается на resource reload (`ArenaOfNationsClient` → `ArenaFighterVisuals.clearTextureCache()`).
- Старые Blockbench-модели (в т.ч. `RuScoutModel`) **не используются** в активном renderer.
- Полноценные фракционные скины **временно отложены**.

Ключевые файлы: `ArenaFighterRenderer`, `ArenaFighterHumanoidModel`, `ArenaFighterVisuals`, `ArenaOfNationsClient`.

---

## 6. Оружие

Только визуал (`ArenaFighterEquipmentVisuals` + `ArenaFighterHeldItemLayer`):

| Класс | Main | Off |
|---|---|---|
| Боец (SCOUT) | trident | — |

- Не кладётся в equipment slots.
- Не меняет урон / loot / атрибуты.
- `translateToHand` от parent `PlayerModel`.

---

## 7. Анимация и AI боя

- `ArenaFighterEntity` пока наследует `Wolf`.
- `registerGoals()` **не** вызывает `super.registerGoals()`.
- Нет `LeapAtTargetGoal`, Sit/Beg/FollowOwner, волчьих target goals.
- Goals: `FloatGoal`, `ArenaFighterMeleeAttackGoal` (**свой Goal**, прямой chase; не extends `MeleeAttackGoal`), `LookAtPlayerGoal`, `RandomLookAroundGoal`.
- Цели: `FighterTargeting` (не targetSelector); living chase только melee goal (`moveTo(target)`).
- Прыжок при атаке убран.
- Один `mob.swing(InteractionHand.MAIN_HAND, true)` в начале атаки (melee windup и core windup); в `doHurtTarget` swing нет.
- Анимация удара — **ванильный** swing `PlayerModel` через `attackTime` (ArmPose ITEM/EMPTY, без своей трёхфазной анимации).
- **`ArenaFighterEntity.aiStep` вызывает `updateSwingTime()`** — у `Player` это делает `serverAiStep`/client tick; у `Wolf` вызова нет, поэтому без этого `attackAnim` оставался 0 и рука не махала (выглядело как «укус»).
- `getMainArm()` → `HumanoidArm.RIGHT`.
- Задержка урона: `WINDUP_TICKS = LivingEntity.SWING_DURATION / 2` → **3 тика** после swing.
- **Melee reach (реализовано):** `ArenaFighterMeleeRange` — start **2.35** XZ center, confirmation **2.95**, vertical max **2.0**, humanoid LoS (eye Y+1.25 → target Y+1.0 + vanilla sensing). Edge distance только для диагностики. Не опирается на vanilla `Mob.isWithinMeleeAttackRange` как единственный gate.
- **Melee goal (реализовано):** `ArenaFighterMeleeAttackGoal` только с `Goal.Flag.LOOK` (**без** `MOVE`) — иначе GoalSelector останавливал goal при `navigation.isDone()` и windup не стартовал. Fast repath 2 тика при nav done вне range.
- **Sticky target:** `FighterTargeting` сохраняет живую цель в радиусе **14** блоков; не меняет цель во время `meleeWindupActive`; переключение только при смерти/удалении/elimination/выходе за радиус.
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

## 8. Клиентские эффекты

`ArenaFighterVisualEffects` (клиент, радиус ~**96**):

| Tier | Эффект |
|---|---|
| SCOUT | `CLOUD` у ног при движении (интервал 6) |
| WARRIOR | `CRIT` при атаке ×5 |
| HEAVY | `CRIT` ×6 + `SMOKE` ×2 при атаке |
| HERO | idle `ENCHANTED_HIT` ×2 (интервал 15); атака `CRIT` ×6 |
| TITAN | движение `POOF` ×3 (интервал 5); атака `CRIT` ×8 |

Движение: `xOld`/`zOld`. Состояние чистится при выгрузке сущности и `DISCONNECT`.

Смерть бойца (сервер, один раз в `ArenaFighterEntity.die`): `POOF`×10 + `CRIT`×6 + `SMOKE`×4, звук `PLAYER_ATTACK_CRIT` (~0.55). Loot/XP не дропаются.

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

Внутри `ArenaMatchManager` (отдельного `ArenaReserve*` нет):

- `/arena_gift <country> <coins>` создаёт `coins` pending-бойцов (класс «Боец»); каждый pending проходит через тот же лимит поля/резерва.
- Ограничение `max_waiting_fighters` больше не режет число живых на поле в live-режиме.
- Переполнение gift → `Queue<PendingFighter>` резерва страны.
- В BATTLE каждые `reserve_wave_interval_ticks` (default **40**) выпускается до `reserve_wave_size` (default **10**) на страну.
- Формула выпуска волны в `releaseReserveWaves`: `min(reserve_wave_size, queueSize)`; волны идут только в `BATTLE`.
- Очистка: timeout waiting, конец боя, elimination, round reset.
- Проверка: `/arena_test_scenario reserve`.

---

## 12. Ядра стран

Подтверждено кодом:

- Позиции (`ArenaCountryBaseLayout` / `ArenaPositions`): **slot-based** — core ring **58**, approach **52**, spawn zone **46**; pedestal ниже ядра. Старые cardinal RU/UA/KZ/BY координаты **не используются**.
- HP ядра: `core_max_health` default **200**; DAMAGED ≤50%.
- **Защита вышки:** `ArenaCoreManager.isCoreProtected(level, country)` — чистый запрос (без side effects); всегда считает защитников на **fight level** (`ArenaSpawns.resolveFightLevel`). Резерв без активной сущности **не** защищает. Сообщения о смене статуса — только из `updateCoreProtectionStates(server)` (один раз за server tick в `ArenaMatchManager.tickBattle`, после release резерва). `FighterTargeting` / HUD / команды статуса сообщения **не** отправляют.
- Атака ядра (`ArenaCoreCombatManager`): range **3.5**, cooldown **20** тиков, windup **3** тика (`SWING_DURATION/2`), урон = `ATTACK_DAMAGE` бойца через `damageFromFighter`; подход — `getCoreAttackPosition`. Защищённую вышку AI не выбирает (`FighterTargeting` + `findNearestAttackableCore`); проверки защиты перед подходом, windup и фактическим уроном; удар отменяется, если защитник появился во время windup. Заблокированный урон не попадает в `coreDamageDealt`. Операторский `/arena_core_damage` обходит защиту.
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
- SavedData: `ArenaScoreSavedData` (`arena_of_nations_scores`), `ArenaSetupSavedData` (`arena_of_nations_setup`).

---

## 14. Арена

`ArenaPositions` / `ArenaCountryBaseLayout` (physical v4):

- `CENTER_PATTERN_RADIUS=42`, `COMBAT_WALKABLE_RADIUS=64`, `SPAWN_ZONE_RADIUS=52`, `CORE_RING_RADIUS=67`, `OUTER_WALL_RADIUS=86`, `CLEAR_RADIUS=92`, `WALL_HEIGHT=12`.
- **20** физических баз-крепостей; ворота открыты в центр, центральный бордюр без коллизии (inner ring walls убраны).
- Build rate: `BLOCKS_PER_TICK = 500`. Стадии: clear → foundation → floor (до walkable radius) → sectors → inner ring (skip/no collision) → stands (74–82) → outer wall (86) → portal markers → lighting → **CORES (20 bases)** → finalize.
- Команды: `/arena_build confirm`, `/arena_build_status`, `/arena_cancel_build confirm`, `/arena_setup_clear confirm` (очистка до radius 92).
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
- `/arena_s2e_chat|gift|status` (код есть; фокус интеграции отложен)

### Спавн
- `/arena_spawn <country>` (спавнит только класс «Боец»)
- `/arena_team_duel`
- `/arena_clear`
- `/arena_demo_four`

### Тестовые сценарии
- `/arena_test_scenario [reset|…|twenty_countries|twenty_countries_mass|countries_joining]`
- `/arena_country_layout_status` — слоты/углы/core/spawn/path участников и все 20 слотов если раунд пуст
- `/arena_country_layout_validate` — полная проверка 20 слотов (пересечения, spawn, path, cores)
- `/arena_country_layout_debug on|off` — частицы base/core/spawn/path (оператор)

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
- `/arena_build [confirm]`, `/arena_build_status`, `/arena_cancel_build [confirm]`, `/arena_setup_clear [confirm]`

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
- Под флагом: «СТРАНА · CODE», «ЯДРО hp/max», полоска HP (~5.4×0.36), статус.
- Дистанция: полный до **100**, fade до **120**; >85 блоков — только код страны.
- Depth test включён; тонкая тёмная рамка; default **on** (`/arena_base_markers`).
- Legacy `ArenaCoreDisplayManager` TextDisplay и `ArenaBaseCodeDisplay` ArmorStand **отключены** (clear/hide сохранены).
- Core damage **не** перестраивает блоки базы; фонари на stone brick + chain; `UPDATE_SUPPRESS_DROPS`.

### Внешний browser overlay
- Desktop preview: `http://127.0.0.1:8765/overlay`
- **TikTok vertical:** `http://127.0.0.1:8765/overlay/tiktok` (canvas **1080×1920**, прозрачный фон, OBS Browser Source)
- Query: `?scale=0.9`, `?preview=1` (рамки safe zones)
- JSON API: `GET /api/arena/state` (read-only, sequence-based snapshot; `Cache-Control: no-store`)
- Thread-safety: snapshot генерируется на server tick (`ArenaOverlayStateService`) и хранится как immutable JSON в `AtomicReference`; HTTP поток читает только готовый snapshot.
- **`ArenaOverlayStateService.pushNow(server)`** — немедленная публикация snapshot (вызывается из `ArenaRoundHudSync.pushNow`, test-сценариев, `/arena_overlay_dump`); во время BATTLE дополнительно не чаще **4 раз/с** (250 ms).
- Snapshot строится из **`getCurrentRoundCountries()`** + active + eliminated (стабильный порядок по `baseSlot`); не зависит от client HUD mode.
- Static assets: `overlay/{index.html,style.css,app.js}` + `overlay/tiktok/{index.html,tiktok.css,tiktok.js}` + `overlay/flags/*.svg`
- TikTok layout: верх (бренд/фаза/таймер/число стран) + две колонки по 10 стран слева/справа; центр свободен; временные event banners 2–4 с.
- CSS vars: `--safe-top/right/bottom/left`, `--panel-width`, `--row-height`, `--overlay-scale`.
- SVG-флаги: 20 оригинальных файлов `flag-icons` + лицензия `overlay/licenses/flag-icons-LICENSE.txt`.
- JS: относительный `/api/arena/state`, poll **250 ms**, пустые состояния «Раунд не начат» / «Нет соединения».
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
- фонари больше не выпадают при атаке ядра — **подтверждено в Minecraft**.

Сборка `gradlew.bat build` доведена до **BUILD SUCCESSFUL** (28.07, full_country_lifecycle).

---

## 17. Отложено

Не предлагать без прямого запроса пользователя:

- полноценные скины четырёх фракций / формации как ближайший этап;
- собственные модели оружия;
- дополнительные массовые VFX сверх уже усиленных ульт ELITE/CHAMPION/TITAN;
- StreamToEarn / TikTok API / внешний HTTP-WebSocket вход подарков как ближайший этап.

---

## 18. Текущий фокус

Разработка и улучшение самой игры Arena of Nations: игровой процесс, боевая система, классы, арена, интерфейс, зрелищность и баланс. Внешняя стрим-интеграция пока отложена.

---

## 19. Последние изменения

~15–20 последних этапов по коду. Без простыней Java.

1. **Humanoid foundation** — wide PlayerModel, Steve fallback, visual weapons, tier client VFX, flags + overhead HP.
2. **Melee AI** — свои goals без wolf leap; `ArenaFighterMeleeAttackGoal`; windup **3** тика; sticky target; reach 2.35/2.95; **без** `Goal.Flag.MOVE`.
3. **Client round HUD** — S2C `round_hud` + `ArenaRoundHudRenderer`; GUI flag blit full 64×40→20×12.
4. **Positioning experiment** — удалён; прямой chase + `getCoreAttackPosition`.
5. **Legacy abilities/VFX** — ELITE/CHAMPION/TITAN ability-код и VFX-pass сохранены в проекте как legacy-контекст.
6. **Economy + core protection** — tiebreak core HP%→damage; `isCoreProtected` на fight level; фикс fight-level tick/spam.
7. **Melee test placement** — `ArenaTestMeleePlacement` (линии X=±4; density ±6..±10). Реализовано.
8. **Humanoid arm swing** — `ArenaFighterEntity.aiStep()` → `updateSwingTime()` (Wolf не крутит swing; humanoid рука машет).
9. **BossBar off** — `bossBarEnabled=false`; `/arena_hud` = client HUD; `/arena_hud bossbar` = отладка.
10. **Death feedback** — `die`: POOF/CRIT/SMOKE + crit sound; пустой `dropAllDeathLoot`, `shouldDropExperience=false`.
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
31. **full_country_lifecycle E2E (28.07)** — сценарий `/arena_test_scenario full_country_lifecycle` (RU/UA/KZ): 7 стадий — PROTECTED → last defender → real core attack → RESCUE+gift → final elimination → round continues → winner RU. Реальные механики: fighter melee, `ArenaCoreCombatManager`, rescue C, elimination, winner. Overlay `pushNow` на переходах. Команда `/arena_lifecycle_status`. Minecraft: большие флаги баз и отсутствие дропа фонарей уже подтверждены пользователем. Проверено **BUILD SUCCESSFUL**; lifecycle — ручной прогон в Minecraft.

Заменено ранее:

- wolf/Blockbench renderer → humanoid PlayerModel;
- custom трёхфазная атака → vanilla swing + `updateSwingTime`;
- name tags → флаги + HP;
- BossBar как основной UI → client round HUD (BossBar debug-only);
- «ВЫБЫЛА при coreHP≤0» / «донат обязателен при живых бойцах» → variant C.

---

## Правила обновления этого файла

После **каждого** законченного задания агент обязан обновить этот документ (см. `.cursor/rules/project-status-memory.mdc`): факты из кода, не планы; не раздувать журнал; не трогать `STREAMTOEARN_SETUP.md`, если задача не про StreamToEarn.
