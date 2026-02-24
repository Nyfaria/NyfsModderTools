# NyfsModderTools

An IntelliJ IDEA plugin for Minecraft mod development, providing powerful tools for working with Mixins, Access Transformers/Wideners, and more.

## Features

### Copy Actions

#### Copy AT/AW (Ctrl+Alt+A)
Right-click on any class, method, or field in Minecraft code to copy the Access Transformer (Forge/NeoForge) or Access Widener (Fabric) entry to your clipboard.

#### Copy Mixin Target (Ctrl+Alt+M)
Copy properly formatted Mixin target strings for use in `@Inject`, `@Redirect`, `@Shadow`, `@At(value="INVOKE")`, and other Mixin annotations.

### Mixin Autocomplete

Smart autocomplete that understands Mixin semantics:

- **`method=""`** - Autocomplete method names and descriptors for `@Inject`, `@Redirect`, `@ModifyArg`, `@WrapMethod`, etc.
- **`target=""`** - Autocomplete for `@At` targets (INVOKE, FIELD, NEW) within the context of the containing method

Supports both standard Mixin annotations and Mixin Extras:
- Standard: `@Inject`, `@Redirect`, `@ModifyArg`, `@ModifyArgs`, `@ModifyConstant`, `@ModifyVariable`, `@Overwrite`
- Mixin Extras: `@WrapMethod`, `@WrapOperation`, `@WrapWithCondition`, `@ModifyExpressionValue`, `@ModifyReturnValue`, `@ModifyReceiver`

### Mixin Inspections (Error Checking)

Real-time validation with red/yellow squiggles for common Mixin errors:

- **Invalid method target** - Method specified in `method=""` doesn't exist
- **Invalid @At target** - Target specified in `target=""` doesn't exist within the method
- **Invalid method signature** - Handler method has wrong parameters, missing `CallbackInfo`, wrong `Operation` type, etc.
- **Mixin not registered** - Warning when a Mixin class isn't listed in any `mixins.json` file

### Quick Fixes (Alt+Enter)

- Fix method signatures (adds missing parameters, `CallbackInfo`, `CallbackInfoReturnable`, `Operation<T>`, etc.)
- Add Mixin class to `mixins.json`

### Mixin Generator Actions

#### Generate Mixin Handler (Ctrl+Alt+G)
Right-click on any method in Minecraft code and select "Generate Mixin Handler" to automatically:
- Create a mixin handler method with the correct signature
- Choose from `@Inject`, `@WrapMethod`, `@Redirect`, `@ModifyReturnValue`, or `@Overwrite`
- Find an existing Mixin class targeting that class, or create a new one (`ClassMixin`)
- Add all necessary imports

#### Generate INVOKE Mixin
Right-click on a method call inside Minecraft code to generate a mixin targeting that specific invocation with `@At(value="INVOKE")`.

#### Generate Accessor/Invoker
Right-click on a private field or method to generate an `@Accessor` or `@Invoker` interface method. Creates a new accessor interface (`ClassAccessor`) if none exists.

### Navigation

- **Gutter icons** on mixin handler methods to navigate to the target method
- **Ctrl+Click** on `method=""` and `target=""` strings to navigate to the referenced method/field
- **Implicit usage detection** - Mixin classes in `mixins.json` and annotated methods/fields are shown as "used" (not grayed out)

### Settings

All features can be individually enabled/disabled in **Settings → Tools → Nyf's Modding Plugin**:

- Project Template
- Copy AT/AW Action
- Copy Mixin Target Action
- Mixin Autocomplete
- Mixin Inspections
- Generate Mixin Actions
- Generate Accessor/Invoker Actions

## Requirements

- IntelliJ IDEA 2025.2.4 or newer
- Java plugin enabled

## Installation

### From JetBrains Marketplace
1. Go to **Settings → Plugins → Marketplace**
2. Search for "NyfsModderTools"
3. Click Install

### Manual Installation
1. Download the latest release from the [Releases page](https://github.com/Nyfaria/NyfsModdingPlugin/releases)
2. Go to **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select the downloaded `.zip` file

## Supported Annotations

### Standard Mixin
`@Mixin`, `@Inject`, `@Redirect`, `@ModifyArg`, `@ModifyArgs`, `@ModifyConstant`, `@ModifyVariable`, `@Overwrite`, `@Shadow`, `@Accessor`, `@Invoker`, `@Final`, `@Mutable`, `@Unique`

### Mixin Extras
`@WrapMethod`, `@WrapOperation`, `@WrapWithCondition`, `@ModifyExpressionValue`, `@ModifyReturnValue`, `@ModifyReceiver`

## Keyboard Shortcuts

| Action | Shortcut |
|--------|----------|
| Copy AT/AW | Ctrl+Alt+A |
| Copy Mixin Target | Ctrl+Alt+M |
| Generate Mixin Handler | Ctrl+Alt+G |

## License

This project is licensed under the MIT License.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Author

[Nyfaria](https://www.nyfaria.com)
