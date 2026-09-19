"""
Sprite/constant access for the icon tooling, read straight out of the
shipped Kotlin.

There is deliberately no Python copy of the art any more. Art.kt is the
source of truth; this just parses it.
"""
import ktparse

_sprites, _consts = ktparse.parse()


class _Art:
    pass


A = _Art()
for _k, _v in _sprites.items():
    setattr(A, _k, _v)
for _k, _v in _consts.items():
    setattr(A, _k, _v)

if not hasattr(A, "SPR_PLANE"):
    raise SystemExit("could not read sprites from Art.kt - is the repo intact?")
