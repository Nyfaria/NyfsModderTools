# NyfsModdingPlugin - Feature Roadmap

## Current Features (v1.0.0-beta.2)

### Copy Actions
- [x] **Copy AT/AW** (Ctrl+Alt+A) - Copy Access Transformer/Access Widener entry for Minecraft classes, methods, fields
- [x] **Copy Mixin Target** (Ctrl+Alt+M) - Copy Mixin target strings (@Inject method, @At INVOKE, @Redirect, @Shadow, etc.)

### Mixin Autocomplete
- [x] **method="" autocomplete** - Autocomplete for method targets in @Inject, @Redirect, @ModifyArg, etc.
- [x] **target="" autocomplete** - Autocomplete for @At target values (INVOKE, FIELD, NEW)
- [x] Supports all standard Mixin annotations
- [x] Supports Mixin Extras annotations (WrapMethod, WrapOperation, WrapWithCondition, ModifyExpressionValue, ModifyReturnValue, ModifyReceiver)

### Mixin Inspections (Error Checking)
- [x] **Invalid method target** - Red squiggle when method="" refers to non-existent method
- [x] **Invalid method signature** - Red squiggle when method descriptor doesn't match
- [x] **Invalid @At target** - Red squiggle when target="" refers to non-existent method/field/constructor
- [x] **Invalid Mixin method signature** - Red squiggle when handler method has wrong parameters (missing CallbackInfo, wrong Operation type, etc.)
- [x] **Mixin not registered** - Yellow warning when Mixin class is not in any mixins.json file
  - [x] Quick fix to add to mixins.json

### Quick Fixes
- [x] Fix method signature (suggests correct descriptor)
- [x] Fix @Inject signature (generates correct parameters + CallbackInfo/CallbackInfoReturnable)
- [x] Fix @WrapMethod signature (generates correct parameters + Operation<T>)
- [x] Fix @Redirect return type
- [x] Add mixin to mixins.json

### Implicit Usage Detection
- [x] Mixin classes registered in mixins.json shown as "used" (not grayed out)
- [x] Methods with Mixin annotations (@Inject, @Shadow, etc.) shown as "used"
- [x] Fields with Mixin annotations (@Shadow, @Final, etc.) shown as "used"

### Settings
- [x] Enable/disable project template
- [x] Enable/disable Copy AT/AW action
- [x] Enable/disable Copy Mixin Target action
- [x] Enable/disable Mixin autocomplete
- [x] Enable/disable Mixin inspections

---

## Planned Features

### High Priority

#### Mixin Method Generator
- [x] Right-click on method in target class → "Generate Mixin Handler" (Ctrl+Alt+G)
- [x] Auto-generate full @Inject/@Redirect/@WrapMethod method with correct signature
- [x] Include all parameters and CallbackInfo automatically
- [x] Support for all Mixin and Mixin Extras annotations
- [x] Finds existing mixin targeting the class, or creates new ClassMixin
- [x] Right-click on method call → "Generate INVOKE Mixin" for @Inject/@Redirect/@WrapOperation at INVOKE

#### Navigate to Mixin Target / Find Mixins
- [x] Gutter icon on @Mixin class → Go to target class
- [x] Gutter icon on Minecraft class → Show all mixins targeting it
- [x] Gutter icon on mixin handler method → Go to target method

#### @Accessor/@Invoker Generator
- [x] Right-click private field → Generate @Accessor interface method
- [x] Right-click private method → Generate @Invoker interface method
- [x] Auto-create accessor interface if none exists (ClassAccessor)

### Medium Priority

#### Registry Helper
- [ ] Autocomplete for ResourceLocation/ResourceKey
- [ ] Validation that registry entries exist
- [ ] Quick navigation to registry definitions
- [ ] Support for custom registries

#### JSON Schema Support
- [ ] Enhanced autocomplete for fabric.mod.json
- [ ] Enhanced autocomplete for neoforge.mods.toml
- [ ] Enhanced autocomplete for pack.mcmeta
- [ ] Mod ID autocomplete in dependencies
- [ ] Entry point class autocomplete

#### Data Generation Templates
- [ ] Generate BlockState JSON from block class
- [ ] Generate loot table templates
- [ ] Generate recipe templates
- [ ] Generate tag file templates
- [ ] Common patterns (slab from block, stairs from block)

### Lower Priority

#### Asset File Generators
- [ ] Generate model JSON from block/item class
- [ ] Generate lang file entries for registered blocks/items
- [ ] Texture placeholder generation

#### Mixin Preview/Visualization
- [ ] Show merged class preview after mixins applied
- [ ] Highlight injection points in target method
- [ ] Show mixin application order

#### Mod Compatibility Warnings
- [ ] Warn when mixins target commonly-mixined methods
- [ ] Suggest @WrapOperation over @Redirect for compatibility
- [ ] Detect potential mixin conflicts

#### Live Templates
- [ ] @Inject with HEAD snippet
- [ ] @Inject with RETURN snippet
- [ ] @Inject with INVOKE snippet
- [ ] Block/Item registration boilerplate
- [ ] Event handler templates

---

## Technical Debt / Improvements

- [ ] Cache mixins.json parsing results
- [ ] Better error messages for inspection failures
- [ ] Unit tests for inspections
- [ ] Unit tests for completions
- [ ] Performance optimization for large projects

---

## Supported Annotations

### Standard Mixin
- @Mixin
- @Inject
- @Redirect
- @ModifyArg
- @ModifyArgs
- @ModifyConstant
- @ModifyVariable
- @Overwrite
- @Shadow
- @Accessor
- @Invoker
- @Final
- @Mutable
- @Unique

### Mixin Extras
- @WrapMethod
- @WrapOperation
- @WrapWithCondition (including v2)
- @ModifyExpressionValue
- @ModifyReturnValue
- @ModifyReceiver

---

## Version History

### v1.0.0-beta.3
- Added "Generate Mixin Handler" action (Ctrl+Alt+G) - generates @Inject, @WrapMethod, @Redirect, @ModifyReturnValue, @Overwrite
- Added "Generate INVOKE Mixin" action - right-click on method call to generate @Inject/@Redirect/@WrapOperation at INVOKE
- Added "Generate Accessor/Invoker" action - generates @Accessor/@Invoker interface methods
- Added gutter icons for navigation between mixins and targets
- Added Ctrl+click navigation for method= and target= strings
- Finds existing mixins or creates new ClassMixin/ClassAccessor files

### v1.0.0-beta.2
- Added Mixin Extras support (WrapMethod, WrapOperation, etc.)
- Added method signature inspection with quick fixes
- Added "not registered in mixins.json" warning with quick fix
- Added implicit usage provider for Mixin classes/methods/fields
- Import optimization (shortenClassReferences) for quick fixes

### v1.0.0-beta.1
- Initial release
- Copy AT/AW action
- Copy Mixin Target action
- Basic Mixin autocomplete
- Basic Mixin inspections

