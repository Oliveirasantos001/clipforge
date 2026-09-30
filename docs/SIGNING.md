# Assinatura oficial do ClipForge

O APK release oficial usa uma chave privada exclusiva do ClipForge.

## Certificado oficial

SHA-256:

`d37f12ff05a1eaa12a2d42281852ac7150d52df7667bf1628dd385104efbb88c`

Alias:

`clipforge`

A chave privada **não deve ser adicionada ao repositório**.

## GitHub Actions

O workflow `.github/workflows/build-apk.yml` exige o secret:

`CLIPFORGE_SIGNING_BUNDLE`

Esse secret contém o keystore codificado e sua senha. O runner restaura o keystore somente durante o build, gera o APK release, verifica o certificado SHA-256 e apaga o material de assinatura ao final.

O build release falha se a chave de assinatura não estiver configurada.

## Regra de atualização

Todas as futuras versões oficiais devem continuar usando a mesma chave. Perder essa chave impede atualizar instalações existentes assinadas com ela.
