# Matchkeys - goldrush2024

> [!IMPORTANT]
> Deprecated: All matchkeys facilities have moved to a new repository at:
> https://github.com/indexdata/matchkeys
> These facilities still remain at reservoir because they are still used in some deployments.
> Ongoing development happens at the new repository.

This implements the "Gold Rush - Colorado Alliance MARC record match key generation" (specification dated 4 December 2024).

## Status

Each component of the specification is implemented.

This specification removed the component "General Media Designator (GMD)" that was utilised in the 2021 specification, but did not replace it with another component.

## Components

Each component of the matchkey is padded with the underscore character to fill to its field width.

The [diagram](explain-matchkey-goldrush.png) indicates the components.
