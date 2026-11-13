# Extrato de operações de crédito (COBOL)

Batch de geração de extrato de operações de crédito em linha sequencial (132 colunas), com log em arquivo separado. O desenho original é para **IBM z/OS** (VisualAge COBOL / IBM COBOL for MVS), **DB2** e job **JCL** (`EXTCRDJ.jcl`).

## Programas

| Fonte | Função |
|--------|--------|
| `PGMEXTRT.cbl` | Programa principal: orquestra leitura, formatação e gravação |
| `PGMLECRD.cbl` | Leitura via cursor DB2 (`TB_OPERACAO_CREDITO` / `TB_CLIENTE`) |
| `PGMFMTEXT.cbl` | Formata linhas do extrato (cabeçalho, detalhe, rodapé) |
| `PGMGRVEXT.cbl` | Grava linhas no arquivo sequencial de saída |
| `PGMLECRD_mock.cbl` | **Somente ambiente local:** mesmo `PROGRAM-ID PGMLECRD`, sem SQL — devolve dados fixos compatíveis com o DDL de exemplo |

Esquema de dados e índices: `TB_CREDITO_DDL.sql` (schema `DB2PROD`).

## Execução no mainframe (referência)

1. Pré-compilar `PGMLECRD.cbl` (SQL incorporado), compilar e linkar os quatro programas na loadlib referenciada pelo JCL (`STEPLIB`).
2. Ajustar no `EXTCRDJ.jcl` os datasets (`PROD.MAINFRAME.LOADLIB`, `PROD.EXTRATO.CREDITO.*`) e o parâmetro do step `STEP010`:
   - Posições 1–8: data de referência `AAAAMMDD`
   - Posição 9: `/`
   - Posições 10–13: código da agência  
   Exemplo no job: `PARM='20260508/0042'`.
3. **Parâmetro em `PGMEXTRT`:** no código-fonte atual, a área `WS-PARM` deve ser preenchida pelo ambiente batch (tamanho + dados); em execução local isso é tratado com valores padrão (ver abaixo).

## Execução local (macOS / Linux com GnuCOBOL)

Requisito: [GnuCOBOL](https://gnu.org/software/gnucobol/) (`cobc` no PATH). No macOS: `brew install gnu-cobol`.

O programa `PGMLECRD.cbl` **não** compila no GnuCOBOL sem pré-processamento DB2/SQL; para rodar o fluxo completo em máquina local usa-se **`PGMLECRD_mock.cbl`** no lugar do módulo real. Esse módulo inclui no build o ficheiro **`mock-generated.inc`**: é gerado por **`scripts/gen-mock-from-dat.awk`** a partir de **`mock-operacoes.dat`** (uma operação por linha, campos separados por `|`). **Não é necessário subir PostgreSQL nem Docker** para desenvolvimento local: o `.dat` é o “cadastro” que alimenta o batch; basta editar o ficheiro (ou usar o CLI) e correr `make`. Se não existir `mock-operacoes.dat`, o `make` gera um include vazio e o programa usa a tabela padrão do parágrafo `1010-INIT-TABELA-PADRAO`.

### Uso opcional com Docker

Docker é uma alternativa ao fluxo nativo acima; não inicia DB2, PostgreSQL nem qualquer serviço, e executa apenas o batch local com `PGMLECRD_mock.cbl`. Na raiz do repositório:

```bash
docker build -t banking-va-cobol .
docker run --rm -it --user "$(id -u):$(id -g)" -v "$PWD:/app" banking-va-cobol
```

O segundo comando abre o CLI interativo. Para invocar comandos diretamente (por exemplo, listar e gerar):

```bash
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/app" banking-va-cobol listar
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/app" banking-va-cobol gerar
```

O bind mount da raiz permite ao CLI ler/alterar `mock-operacoes.dat` e deixa `mock-generated.inc`, `bin/`, `SAIDA` e `LOGFILE` acessíveis no host. O contexto de build exclui os dados locais e as saídas geradas, para não os incorporar à imagem. Sem o bind mount, alterações e saídas ficam no filesystem efémero do container; o `--user` evita criar ficheiros pertencentes a `root` no host.

### Operações locais: registar → listar → gerar extrato

O programa COBOL **não** pede dados em ecrã: ele usa a lista já compilada a partir de **`mock-operacoes.dat`**. O fluxo é:

1. **Registar** uma ou mais operações (ficheiro texto).
2. **Listar** o que ficou registado (confirmar antes do batch).
3. **Gerar** o extrato: `make` inclui a lista no código e corre o batch.

**Modo interativo (recomendado):** na raiz do repositório, execute **sem argumentos**. Abre uma “tela” no terminal com o prompt **`extrato>`**; digite os comandos ali até **`sair`** (ou `exit` / `q`), sem precisar voltar a invocar o script a cada passo.

```bash
./scripts/extrato-cli.sh
```

No prompt: **`ajuda`** lista comandos; **`registrar`** sem mais texto pergunta os campos; **`listar`**, **`gerar`**, **`show`**, **`limpar`**, etc. Alternativa com menu numérico (1–5): digite **`menu`** dentro do mesmo prompt.

Fluxo equivalente em linha de comando (cada ordem é uma execução do script):

```bash
./scripts/extrato-cli.sh registrar 0042123456 "2026/OP0001    " CPE "MEU CREDITO         " 10000.00 12 3 8000.50 20261201 A
./scripts/extrato-cli.sh listar
./scripts/extrato-cli.sh gerar
```

Sinónimos: **`add`** = **`registrar`**; **`run`** = **`gerar`** = **`extrato`**; **`list`** = **`listar`** = **`operacoes`**. **`registrar-interativo`** (só ao invocar o script com esse argumento) pergunta campo a campo, como **`registrar`** sozinho no prompt interativo.

- **`show`** — mostra o `.dat` em bruto (`|`).
- **`clear`** / **`limpar`** — apaga a lista e zera `mock-generated.inc`.

**Ordem dos 10 campos** ao registar: conta, contrato (até 14), modalidade (3), descrição (até 20), valor original, parcelas, pagas, saldo, data `AAAAMMDD`, status (`A`/`I`/…).

O **`gerar`** faz **`make`** (obrigatório após cada alteração à lista): o COBOL embute os dados no include na **compilação**, não lê o `.dat` em tempo de execução. Por defeito **só imprime as secções SAIDA e LOGFILE** (sem barulho do compilador nem avisos `libcob`; se a compilação ou o batch falharem, mostra a saída completa).

**Nota:** a primeira leitura do `PGMEXTRT` no arranque consome o primeiro registo do mock (comportamento do `CALL` de init); com uma única linha no ficheiro pode não aparecer linha de detalhe — use pelo menos **duas** operações para ver o efeito no extrato.

### Compilar

```bash
make
```

Equivalente manual (gerar include + compilar):

```bash
awk -f scripts/gen-mock-from-dat.awk mock-operacoes.dat > mock-generated.inc   # ou /dev/null se não houver .dat
mkdir -p bin
cobc -std=mvs -x -o bin/extrato PGMEXTRT.cbl PGMFMTEXT.cbl PGMGRVEXT.cbl PGMLECRD_mock.cbl
```

O dialeto `-std=mvs` evita ambiguidade com **nomes de parágrafo** que começam por dígitos (comuns em COBOL mainframe).

### Rodar

```bash
make run
```

Ou:

```bash
./bin/extrato
```

Saídas no diretório atual:

- **`SAIDA`** — extrato: registros de **132 bytes** cada, **sem quebra de linha entre registros** (é arquivo “mainframe FB”). Por isso o `cat SAIDA` parece uma linha só.
- **`LOGFILE`** — mesmo formato de registro (132 caracteres).

**Ver listagem no terminal (recomendado):**

```bash
make run-show          # roda o batch e imprime SAIDA + LOGFILE numerados
```

Só listar (depois de já existir `SAIDA` / `LOGFILE`):

```bash
make list              # equivalente: ./scripts/list-extrato.sh SAIDA
make list-log          # ./scripts/list-log.sh LOGFILE
```

Equivalente manual com `fold`:

```bash
fold -b -w132 SAIDA | nl -ba
```

Se `WS-PARM` vier vazio (como em execução direta sem JCL), o programa usa **data `20260508` e agência `0042`** como padrão para teste local.

## Conteúdo do repositório

| Arquivo | Descrição |
|---------|-----------|
| `EXTCRDJ.jcl` | Job batch (principal `PGMEXTRT`, datasets de saída e log) |
| `TB_CREDITO_DDL.sql` | DDL DB2 e inserts de exemplo |
| `Makefile` | Build (gera `mock-generated.inc`), `run`, `run-show`, `list`, `list-log` |
| `mock-operacoes.dat` | Dados opcionais para o mock (editado pelo CLI ou à mão) |
| `mock-generated.inc` | Gerado pelo `Makefile` / `awk` — incluído em `PGMLECRD_mock.cbl` |
| `scripts/gen-mock-from-dat.awk` | Converte `mock-operacoes.dat` em parágrafo COBOL `0520-APLICAR-MOCK-DAT` |
| `scripts/extrato-cli.sh` | Sem argumentos: prompt interativo `extrato>`; ou comandos `add`, `listar`, `gerar`, etc. por linha |
| `scripts/list-extrato.sh`, `scripts/list-log.sh` | Quebram registros de 132 bytes e mostram no CLI com numeração |

## Limitações da execução local

- Não há DB2; os dados locais vêm do **`mock-operacoes.dat`** (convertido em **`mock-generated.inc`** no `make`) ou, na ausência desse ficheiro, da tabela embutida em **`1010-INIT-TABELA-PADRAO`**. Para alterar dados sem editar `.cbl`, use **`./scripts/extrato-cli.sh add`** e **`make`**. No **mainframe**, a fonte real é o DB2 (`TB_OPERACAO_CREDITO`): inserts ou cargas como em `TB_CREDITO_DDL.sql`.
- Comportamento de arquivos e `CALL` segue GnuCOBOL; podem aparecer avisos em tempo de execução (por exemplo, fechamento implícito de arquivo) que não ocorrem no ambiente z/OS.
- O programa-fonte `PGMLECRD.cbl` permanece como referência para **bind real com DB2** no mainframe.
