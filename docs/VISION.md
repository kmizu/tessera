# Tessera Vision

Tessera aims to make theorem proving look like ordinary dependently-typed
programming. Proofs are ordinary terms; automation is expression-valued and
composable.

The first milestone is a small closed-kernel vertical slice:

- minimal `Term` and `Sort`
- kernel checker for closed terms
- explicit term inspection commands
- explicit `Synth` combinator layer in later phase
