#!/bin/bash

# =============================================================================
# CodeExplorer — Script de Setup do Pipeline de Snapshots Visuais
# =============================================================================
# Uso: ./setup-codeexplorer-pipeline.sh <caminho-do-projeto>
# Exemplo: ./setup-codeexplorer-pipeline.sh /home/sofia/projetos/spring-petclinic
# =============================================================================

set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Diretório onde o script está (raiz do CodeExplorer)
CODEEXPLORER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Funções de log ────────────────────────────────────────────────────────────
info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[OK]${NC} $1"; }
warn()    { echo -e "${YELLOW}[AVISO]${NC} $1"; }
error()   { echo -e "${RED}[ERRO]${NC} $1"; exit 1; }

# ── Banner ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║        CodeExplorer — Pipeline Setup         ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════╝${NC}"
echo ""

# ── Verificar argumento ───────────────────────────────────────────────────────
if [ -z "$1" ]; then
    error "Informe o caminho do projeto.\nUso: ./setup-codeexplorer-pipeline.sh <caminho-do-projeto>"
fi

TARGET_DIR="$1"

# ── Verificar se o diretório existe ───────────────────────────────────────────
if [ ! -d "$TARGET_DIR" ]; then
    error "Diretório não encontrado: $TARGET_DIR"
fi

info "Projeto alvo: $TARGET_DIR"

# ── Verificar se é um repositório Git ─────────────────────────────────────────
if [ ! -d "$TARGET_DIR/.git" ]; then
    error "O diretório não é um repositório Git: $TARGET_DIR\nInicialize com: git init"
fi

success "Repositório Git encontrado"

# ── Verificar se tem src/main/java ────────────────────────────────────────────
HAS_SRC=true
if [ ! -d "$TARGET_DIR/src/main/java" ]; then
    warn "src/main/java não encontrado. O teste local do extrator será pulado."
    HAS_SRC=false
else
    success "src/main/java encontrado"
fi

# ── Verificar dependências necessárias ────────────────────────────────────────
info "Verificando dependências..."

command -v java  >/dev/null 2>&1 || error "Java não encontrado. Instale o JDK 17+."
command -v mvn   >/dev/null 2>&1 || error "Maven não encontrado. Instale o Maven."
command -v node  >/dev/null 2>&1 || error "Node.js não encontrado. Instale o Node.js 18+."
command -v git   >/dev/null 2>&1 || error "Git não encontrado."

success "Java:   $(java -version 2>&1 | head -1)"
success "Maven:  $(mvn -version 2>&1 | head -1)"
success "Node:   $(node --version)"

# ── Verificar se headless/ existe no CodeExplorer ─────────────────────────────
if [ ! -d "$CODEEXPLORER_DIR/headless" ]; then
    error "Pasta headless/ não encontrada em $CODEEXPLORER_DIR\nCertifique-se de rodar este script a partir da raiz do CodeExplorer."
fi

# ── Copiar headless/ para o projeto alvo ──────────────────────────────────────
echo ""
info "Copiando arquivos do pipeline..."

if [ -d "$TARGET_DIR/headless" ]; then
    warn "Pasta headless/ já existe no projeto. Sobrescrevendo..."
fi

cp -r "$CODEEXPLORER_DIR/headless" "$TARGET_DIR/"
success "Pasta headless/ copiada"

# ── Criar estrutura do GitHub Actions ─────────────────────────────────────────
mkdir -p "$TARGET_DIR/.github/workflows"

if [ -f "$TARGET_DIR/.github/workflows/codeexplorer.yml" ]; then
    warn "codeexplorer.yml já existe. Sobrescrevendo..."
fi

cp "$CODEEXPLORER_DIR/.github/workflows/codeexplorer.yml" \
   "$TARGET_DIR/.github/workflows/codeexplorer.yml"
success "GitHub Actions workflow copiado"

# ── Verificar se tem Bitbucket e copiar também ────────────────────────────────
BB_COPIED=false
if [ -f "$TARGET_DIR/bitbucket-pipelines.yml" ]; then
    warn "bitbucket-pipelines.yml já existe no projeto — não sobrescrevendo."
    warn "Adicione manualmente o step do CodeExplorer se quiser suporte ao Bitbucket."
else
    cp "$CODEEXPLORER_DIR/bitbucket-pipelines.yml" "$TARGET_DIR/"
    success "Bitbucket Pipelines copiado"
    BB_COPIED=true
fi

# ── Build do extrator standalone ──────────────────────────────────────────────
echo ""
info "Compilando o extrator standalone..."

cd "$TARGET_DIR/headless"
mvn package -q 2>&1

if [ ! -f "$TARGET_DIR/headless/target/extractor-cli.jar" ]; then
    error "Falha ao compilar o extrator. Verifique os logs do Maven."
fi

success "extractor-cli.jar gerado com sucesso"

# ── Instalar dependências Node ─────────────────────────────────────────────────
echo ""
info "Instalando dependências Node.js (Puppeteer)..."
info "Isso pode demorar alguns minutos na primeira vez..."

npm install --silent 2>&1
success "Dependências Node instaladas"

# ── Teste local rápido ────────────────────────────────────────────────────────
echo ""
if [ "$HAS_SRC" = true ]; then
    info "Rodando teste local..."

    mkdir -p output

    EXTRACT_OUTPUT=$(java -jar target/extractor-cli.jar "$TARGET_DIR/src/main/java" output/graph.json 2>&1)
    echo "$EXTRACT_OUTPUT"

    if [ ! -f "output/graph.json" ]; then
        error "Falha ao gerar graph.json. Verifique o caminho do src."
    fi

    # Conta vinda do próprio resumo do extrator ("OK: N classes, ...") em vez
    # de reprocessar o JSON — evita depender da formatação exata do Gson e
    # cobre todos os tipos (CLASS/INTERFACE/ABSTRACT_CLASS/ENUM), não só CLASS.
    CLASSES=$(echo "$EXTRACT_OUTPUT" | grep -oE '[0-9]+ classes' | grep -oE '[0-9]+')
    CLASSES=${CLASSES:-"?"}
    success "graph.json gerado — $CLASSES classes encontradas"

    # Screenshot de teste
    COMMIT="setup000"
    DATE=$(date +%Y-%m-%d)
    node screenshot.js output/graph.json $COMMIT $DATE 2>&1

    if ls output/*.png 1>/dev/null 2>&1; then
        success "Screenshot de teste gerado: $(ls output/*.png | head -1)"
    else
        warn "Screenshot não foi gerado. Verifique se o Puppeteer está funcionando."
    fi
else
    warn "Pulando teste local — src/main/java não existe em $TARGET_DIR."
    CLASSES="?"
fi

# ── Adicionar ao .gitignore ───────────────────────────────────────────────────
cd "$TARGET_DIR"

if [ -f ".gitignore" ]; then
    if ! grep -q "headless/output" .gitignore; then
        echo "" >> .gitignore
        echo "# CodeExplorer" >> .gitignore
        echo "headless/output/" >> .gitignore
        echo "headless/node_modules/" >> .gitignore
        echo "headless/target/" >> .gitignore
        success ".gitignore atualizado"
    else
        info ".gitignore já tem as entradas do CodeExplorer"
    fi
else
    cat > .gitignore << 'EOF'
# CodeExplorer
headless/output/
headless/node_modules/
headless/target/
EOF
    success ".gitignore criado"
fi

# ── Commit inicial ────────────────────────────────────────────────────────────
echo ""
info "Preparando commit inicial..."

# Só adiciona o que este script de fato instalou/tocou — nunca ".github/"
# ou "bitbucket-pipelines.yml" inteiros, que podem conter outros arquivos
# do projeto alvo não relacionados ao CodeExplorer.
git add headless .gitignore
git add .github/workflows/codeexplorer.yml
if [ "$BB_COPIED" = true ]; then
    git add bitbucket-pipelines.yml
fi

STAGED=$(git diff --cached --name-only | wc -l)

if [ "$STAGED" -gt "0" ]; then
    git commit -m "chore: adiciona pipeline CodeExplorer para snapshots visuais por PR"
    success "Commit realizado"
else
    info "Nada novo para commitar"
fi

# ── Resumo final ──────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║              Setup concluído!                ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════╝${NC}"
echo ""
echo "  Projeto configurado: $TARGET_DIR"
echo ""
echo "  Próximos passos:"
echo "  1. git push para subir o pipeline"
echo "  2. Abra um PR no GitHub"
echo "  3. Acesse a aba Actions do repositório"
echo "  4. Baixe os artefatos PNG/PDF gerados"
echo ""
echo "  Para rodar localmente a qualquer momento:"
echo "  cd $TARGET_DIR/headless"
echo "  java -jar target/extractor-cli.jar ../src/main/java output/graph.json"
echo "  node screenshot.js output/graph.json <commit> <data>"
echo ""