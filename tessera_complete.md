# Codex Complete Prompt v2: Tessera
## Scala風・term-first・compositional synthesis・`do`記法・scoped continuationを備えた定理証明言語

> **作業名:** Tessera  
> 名前は仮称。名前依存部分は一箇所へ隔離し、後から一括変更できるようにすること。  
> この文書は、空または既存のリポジトリを Codex に渡し、設計文書と実行可能な最小実装を作らせるための完全な作業指示である。

---

# 0. あなたの役割

あなたは、依存型理論、Calculus of Constructions / Calculus of Inductive Constructions、定理証明系、関数型言語、コンパイラ、メタプログラミング、algebraic effects、delimited continuations、IDE/LSP、Scala 3 に精通したプリンシパル言語設計者兼コンパイラエンジニアである。

既存の Lean / Coq / Agda / Idris の表面構文を安易に混ぜた言語を作ってはならない。

次の中心命題を、構文、型、elaboration、メタ言語、kernel、diagnostics、IDE、tutorial のすべてで一貫させよ。

> **証明とは、普通の依存型関数プログラムである。**  
> **証明自動化とは、隠れた proof state を命令的に更新するスクリプトではなく、型付きゴールから検査可能な普通の項を返す、合成可能なメタプログラムである。**

設計文書だけを書いて終わってはいけない。設計を固定した後、その思想を検証できる小さいが実行可能な vertical slice を Scala 3 で実装すること。

---

# 1. 問題意識

従来の tactic UI は、実際には依存型付き AST を生成しているにもかかわらず、ユーザーへ次を強いる。

```text
intro
apply
constructor
cases
exact
focus
next
all_goals
```

この形式には次の問題がある。

- 暗黙の「現在のゴール」がある。
- 暗黙のゴールスタックがある。
- 命令の意味が、隠れた状態とゴール順序に依存する。
- bullet や focus で編集位置を管理する。
- tactic script を脳内実行しないと、生成される関数 AST が分からない。
- proof tree と ordinary functional program の対応がソース上で隠れる。
- subgoal が名前付きの構文要素でなく、匿名のリスト要素として扱われる。
- 数学的な証明手続きの語彙が、プログラム生成の本体より前に出る。

プログラマから見ると、これは概ね次の設計である。

> **AST エディタの操作履歴を、そのままソースコードとして保存している。**

Tessera は、この歴史的 UI を一次設計から捨てる。

---

# 2. 最重要ゴール

1. ユーザーは普通の依存型関数プログラムを書く。
2. 命題は型、証明は値として扱う。
3. theorem の右辺は通常の式である。
4. lambda、application、constructor、match、recursion、transport が主役である。
5. typed hole はソース中の明示的な構文位置に存在する。
6. 自動化は hole の位置へ局所的に埋め込める。
7. メタプログラムは通常の値として合成・再利用・テストできる。
8. subgoal は名前付き・構造化された slot として扱う。
9. 自動生成した ordinary term を常に表示できる。
10. kernel は closed core term だけを独立に検査する。
11. ネストを減らすため、Scala `for` / Haskell `do` 相当の sugar を備える。
12. binder や branch の平坦な記法には、必要なら scoped continuation を使う。
13. ただし continuation を口実に hidden goal stack を復活させない。
14. programmer-facing vocabulary を theorem-prover tradition より優先する。

---

# 3. 絶対に破ってはいけない設計原則

## 3.1 Term-first

標準形は普通の term である。

```scala
theorem andIntro[A: Prop, B: Prop]: A => B => And[A, B] =
  (a: A) => (b: B) =>
    And.intro(a, b)
```

`theorem` は proof mode へ入る command ではない。

原則として `def` と同じ右辺式を持ち、主な差は次である。

- opacity
- proof erasure
- documentation category
- runtime code generation policy

---

## 3.2 Program construct と proof construct を分離しない

| 証明論の語彙 | プログラムとしての本体 |
|---|---|
| implication introduction | lambda |
| implication elimination | function application |
| conjunction introduction | constructor application |
| disjunction elimination | pattern matching |
| induction | structural recursion / recursor |
| equality rewrite | transport / dependent cast |
| contradiction elimination | empty type elimination |
| existential witness | dependent pair construction |

主チュートリアルでは、右側の語彙から説明すること。

---

## 3.3 No hidden current goal

public meta API に、可変な暗黙の `currentGoal` を置いてはならない。

一次 API として禁止するもの:

```text
getGoals
setGoals
currentGoal
popGoal
pushGoal
nextGoal
focus
allGoals
goalIndex
bullet
caseFocus
```

内部実装で queue や stack を使うことは許されるが、観測不能な implementation detail に留める。

---

## 3.4 No goal-list semantics

メタプログラムの公開意味論を次にしてはならない。

```text
ProofState => ProofState
```

特に、次を禁止する。

```text
List[Goal] => List[Goal]
```

public semantics の中心は次とする。

```scala
meta type Synth[A] = Goal[A] => Search[Term[A]]
```

実装上、Scala の型だけで完全に index を表せない場合は `Core.Term` を使ってよい。ただし言語仕様上の意味は型付きとし、生成項を combinator 境界と kernel で再検査すること。

---

## 3.5 Structured and named slots

悪いモデル:

```text
goals = [A, B]
```

望ましいモデル:

```scala
And.intro(
  left  = hole[A],
  right = hole[B]
)
```

メタ言語では:

```scala
construct(And.intro)(
  left  = lookup[A],
  right = lookup[B]
)
```

subgoal の処理順を proof の意味へ入れてはならない。

---

## 3.6 Automation is an expression

自動化は、通常の式の任意の位置に置ける。

```scala
theorem pair[A: Prop, B: Prop](a: A, b: B): And[A, B] =
  And.intro(
    derive(lookup[A]),
    derive(lookup[B])
  )
```

または:

```scala
theorem pair[A: Prop, B: Prop](a: A, b: B): And[A, B] =
  derive(
    construct(And.intro)(
      left  = lookup[A],
      right = lookup[B]
    )
  )
```

`derive` は別言語へ入る command ではなく、expected type を持つ通常の expression である。

---

## 3.7 Sugar flattens syntax, not semantics

ネストを減らす sugar は積極的に導入してよい。

許可する方向:

- Scala `for` comprehension
- Haskell `do` notation
- multi-parameter `fn`
- scoped `param`
- pattern binding
- applicative tuple binding
- typed delimited continuation
- scoped algebraic effect

禁止する方向:

- sugar 専用の mutable proof state
- statement 順序で匿名 goal list を消費する semantics
- bullet/focus の復活
- desugaring 不能な opaque proof command
- generated term を表示できない control construct

---

## 3.8 Kernel checks everything

次は信用しない。

- parser
- macro
- elaborator
- unifier
- synthesis
- simplifier
- proof search
- meta evaluator
- continuation runtime
- source-to-source sugar

最終的な closed core term を小さい kernel が再検査する。

kernel に次を入れてはならない。

- parser
- tactic
- search
- source syntax
- IDE state
- unresolved metavariable
- typed hole
- arbitrary host-language execution
- continuation
- backtracking engine

---

## 3.9 Generated code is inspectable

最低限、次を用意する。

```text
#showTerm declarationName
#showCore declarationName
#showSynth declarationName
#showDesugared declarationName
#traceSynth declarationName
```

意味:

- `#showTerm`: 読みやすい ordinary dependent term
- `#showCore`: kernel が検査した core term
- `#showSynth`: synthesis value の構造
- `#showDesugared`: `for` / `do` / `param` を明示 combinator へ展開した形
- `#traceSynth`: 各 generator と slot の trace

---

## 3.10 Programmer vocabulary first

| 伝統的 tactic 語彙 | 第一候補 |
|---|---|
| intro | `fn`, `lambda`, `param` |
| apply | `call`, `app` |
| constructor | `construct` |
| cases | `match`, `inspect` |
| induction | recursion, fold, recursor |
| exact | `use`, `emit`, `pure` |
| assumption | `lookup`, `fromContext` |
| rw | `transport`, expression-valued `rewrite` |
| simp | `normalize`, `simplify` |
| solve | `derive`, `synthesize` |

互換 alias は後から提供してよいが、主 API と主チュートリアルにしない。

---

# 4. 言語の層構造

```text
Surface source
    ↓ parser / name resolution
Surface AST
    ↓ bidirectional elaboration
Elaborated term with metavariables
    ↓ unification / hole synthesis / meta evaluation
Closed core term
    ↓ kernel check
Accepted declaration
```

メタ言語:

```text
derive(Synth[A])
    ↓ run synthesis against explicit Goal[A]
Search[Term[A]]
    ↓ choose candidate
Term[A]
    ↓ elaborate / close / kernel recheck
ordinary accepted term
```

`for` / `do` / `param`:

```text
surface sugar
    ↓ syntax-directed desugaring
compositional Synth combinators
    ↓ interpretation
ordinary Term[A]
```

---

# 5. 対象となる型理論

最終目標は、小さな Calculus of Inductive Constructions 系 kernel とする。

最低限、設計文書で次を定義する。

- `Prop`
- cumulative universes `Type[0]`, `Type[1]`, ...
- dependent function / Pi
- lambda
- application
- let
- constants
- dependent pair / Sigma
- inductive types
- indexed inductive families
- pattern matching / eliminators
- intensional equality
- `refl`
- transport
- structural recursion
- positivity checking
- termination checking
- opacity / reducibility
- definitional equality

definitional equality:

- beta
- delta
- iota
- zeta

eta は MVP で省略してよいが ADR に記録すること。

---

## 5.1 `Prop`

目標仕様では `Prop` を proof-irrelevant かつ runtime-erased な sort とすることを第一候補とする。

次を ADR 化する。

- predicative / impredicative
- Pi sort rules
- proof irrelevance
- erasure
- elimination from `Prop` to `Type`

MVP の段階化は許す。

1. `Type[u]` のみ
2. `Prop`
3. proof irrelevance
4. erasure
5. impredicativity

実装済みと目標仕様を混同しない。

---

## 5.2 General recursion

trusted proof term に unrestricted general recursion を許さない。

第一候補:

- structural recursion
- または termination checker が受理した recursion

実行用 general recursion を追加する場合は、logical core から隔離する。

---

## 5.3 Equality

propositional equality と Boolean equality を分ける。

候補:

```scala
x === y   // proposition
x == y    // Bool
```

普通の term:

```scala
refl(x)
transport(eq, value)
congrArg(f, eq)
```

悪い UI:

```text
rw [h]
```

望ましい式:

```scala
transport(h, value)
```

または:

```scala
rewrite(value, using = h)
```

`rewrite` は必ず expression-valued にする。

---

# 6. Scala風 surface syntax

MVP は brace-based でよい。Scala 3 風 indentation は後続 phase で追加してよい。

---

## 6.1 宣言

```scala
universe u

def id[A: Type[u]](x: A): A =
  x

opaque def secret[A: Type](x: A): A =
  x

theorem identity[A: Type](x: A): x === x =
  refl(x)
```

---

## 6.2 関数

```scala
A => B
```

dependent function:

```scala
(x: A) => B(x)
```

lambda:

```scala
(x: A) => expression
```

---

## 6.3 Inductive type

```scala
enum Nat: Type {
  case zero
  case succ(pred: Nat)
}
```

```scala
enum And[A: Prop, B: Prop]: Prop {
  case intro(left: A, right: B)
}
```

```scala
enum Or[A: Prop, B: Prop]: Prop {
  case left(value: A)
  case right(value: B)
}
```

indexed family:

```scala
enum Vec[A: Type](n: Nat): Type {
  case nil: Vec[A](Nat.zero)

  case cons[m: Nat](
    head: A,
    tail: Vec[A](m)
  ): Vec[A](Nat.succ(m))
}
```

---

## 6.4 Pattern matching

```scala
def add(x: Nat, y: Nat): Nat =
  x match {
    case Nat.zero =>
      y

    case Nat.succ(n) =>
      Nat.succ(add(n, y))
  }
```

証明も同じ構文で書く。

```scala
theorem andComm[A: Prop, B: Prop](p: And[A, B]): And[B, A] =
  p match {
    case And.intro(a, b) =>
      And.intro(b, a)
  }
```

dependent elimination で motive 推論に失敗した場合のみ、明示 motive を許す。

```scala
value match [motive = (x: T) => P(x)] {
  ...
}
```

---

## 6.5 Ordinary theorem examples

```scala
theorem andIntro[A: Prop, B: Prop]: A => B => And[A, B] =
  (a: A) => (b: B) =>
    And.intro(a, b)
```

```scala
theorem compose[A: Type, B: Type, C: Type](
  f: A => B,
  g: B => C
): A => C =
  (a: A) =>
    g(f(a))
```

```scala
theorem orElim[A: Prop, B: Prop, C: Prop](
  value: Or[A, B],
  onA: A => C,
  onB: B => C
): C =
  value match {
    case Or.left(a)  => onA(a)
    case Or.right(b) => onB(b)
  }
```

```scala
theorem addZeroRight(n: Nat): add(n, Nat.zero) === n =
  n match {
    case Nat.zero =>
      refl(Nat.zero)

    case Nat.succ(k) =>
      congrArg(Nat.succ, addZeroRight(k))
  }
```

---

# 7. Typed holes

syntax:

```scala
_
?body
?body: ExpectedType
```

各 hole は次を持つ。

- stable ID
- optional source name
- source span
- local context
- expected type
- universe constraints
- origin
- synthesis trace

表示例:

```text
hole ?right
at examples/And.tes:12:7

context:
  A : Prop
  B : Prop
  a : A
  b : B

expected:
  B

origin:
  field `right` of constructor `And.intro`
```

「goal 2」ではなく、名前と構文位置で示す。

unresolved hole を含む declaration を kernel environment に追加しない。

---

# 8. Compositional synthesis language

## 8.1 Conceptual types

```scala
meta type Term[A]
meta type Local[A] <: Term[A]
meta type Goal[A]
meta type Synth[A]
meta type Search[A]
meta type Refinement[A]
```

概念上:

```scala
Synth[A] = Goal[A] => Search[Term[A]]
```

---

## 8.2 Refinement model

```text
Refinement[A] =
  exists S1 ... Sn.
  {
    slots:
      {
        name1: Goal[S1],
        ...
        namen: Goal[Sn]
      },

    rebuild:
      {
        name1: Term[S1],
        ...
        namen: Term[Sn]
      } => Term[A]
  }
```

要件:

- slot は名前付き。
- slot は immutable value。
- `rebuild` が ordinary term を作る。
- slot の処理順は意味論に含めない。
- 独立 slot は applicative に合成できる。
- dependent slot のみ明示 `flatMap` を使う。

---

## 8.3 Basic combinators

```scala
meta def use[A](term: Term[A]): Synth[A]
meta def pure[A](term: Term[A]): Synth[A]
meta def fail[A](message: String): Synth[A]
```

```scala
meta def fn[A, B](
  body: Local[A] => Synth[B]
): Synth[A => B]
```

dependent:

```scala
meta def depFn[A, B](
  body: (x: Local[A]) => Synth[B(x)]
): Synth[(x: A) => B(x)]
```

```scala
meta def call[A, B](
  function: Term[A => B],
  argument: Synth[A]
): Synth[B]
```

```scala
meta def construct[C](
  constructor: Constructor[C]
)(
  fields: NamedSynthArguments
): Synth[C]
```

```scala
meta def inspect[S, R](
  scrutinee: Term[S]
)(
  branches: Branches[S, R]
): Synth[R]
```

```scala
meta def lookup[A]: Synth[A]
meta def choose[A](first: Synth[A], second: Synth[A]): Synth[A]
meta def label[A](name: String, source: Synth[A]): Synth[A]
meta def search[A](config: SearchConfig): Synth[A]
```

```scala
extension [A](self: Synth[A])
  meta def map[B](f: Term[A] => Term[B]): Synth[B]
  meta def flatMap[B](f: Term[A] => Synth[B]): Synth[B]
  meta def orElse(other: => Synth[A]): Synth[A]
```

```scala
meta def zip[A, B](
  left: Synth[A],
  right: Synth[B]
): Synth[(Term[A], Term[B])]
```

dependent `flatMap` も設計すること。

---

# 9. Scala `for` / Haskell `do`

`Synth` は compositional でも、手書きの `flatMap` nesting は読みにくい。

そのため、Scala の `for` comprehension を通常の monadic sugar として提供する。

```scala
derive(
  for {
    a <- lookup[A]
    b <- lookup[B]
  } yield And.intro(a, b)
)
```

概念上:

```scala
derive(
  lookup[A].flatMap { a =>
    lookup[B].map { b =>
      And.intro(a, b)
    }
  }
)
```

Haskell 風 syntax も許す。

```scala
derive do {
  a <- lookup[A]
  b <- lookup[B]
  yield And.intro(a, b)
}
```

両者の semantics は同じでなければならない。

---

## 9.1 `do` grammar

第一候補:

```text
DoBlock ::=
  "do" "{" DoStmt* YieldStmt "}"

DoStmt ::=
    Pattern "<-" SynthExpr
  | "let" Pattern "=" OrdinaryExpr
  | "param" Identifier ":" TypeExpr
  | "guard" SynthExpr
  | SynthExpr

YieldStmt ::=
  "yield" OrdinaryExpr
```

MVP では final `yield` を必須としてよい。

意味:

```text
x <- synthExpr
rest
```

desugars to:

```scala
synthExpr.flatMap { x =>
  desugar(rest)
}
```

```text
let x = expr
rest
```

desugars to ordinary lexical let.

```text
yield term
```

desugars to:

```scala
use(term)
```

裸の expression statement を許す場合、その値を捨てる意味と failure semantics を明示すること。MVP では禁止してよい。

---

## 9.2 Applicative binding

独立した synthesis は、意味のない sequential dependency を導入しない。

```scala
derive(
  for {
    (a, b) <- zip(lookup[A], lookup[B])
  } yield And.intro(a, b)
)
```

`construct` の field synthesis は第一候補として applicative に解釈する。

```scala
construct(And.intro)(
  left  = lookup[A],
  right = lookup[B]
)
```

field order は diagnostics と deterministic search order には使ってよいが、proof semantics にしない。

---

## 9.3 Desugaring invariants

- `for` / `do` は既存 combinator へ機械的に展開する。
- 専用 mutable proof-state interpreter を作らない。
- explicit combinator 版と同じ generated term を作る。
- source map は元 statement の位置を保持する。
- failure trace は元 binding 名を表示する。
- nested `do` を通常の Synth value として再利用できる。
- `#showDesugared` で展開結果を表示できる。
- `#showTerm` で ordinary term を表示できる。

---

# 10. Scoped `param` and continuations

通常の monadic binding だけでは、lambda binder が生む result type の変化を平坦化できない。

明示 combinator:

```scala
fn[A] { a =>
  fn[B] { b =>
    use(And.intro(a, b))
  }
}
```

平坦な syntax:

```scala
meta def andIntroSynth[A: Prop, B: Prop]
  : Synth[A => B => And[A, B]] =
  synth do {
    param a: A
    param b: B
    yield And.intro(a, b)
  }
```

これは必ず次へ desugar する。

```scala
fn[A] { a =>
  fn[B] { b =>
    use(And.intro(a, b))
  }
}
```

---

## 10.1 `param` is not `intro`

`param a: A` は、現在のゴールを変更する command ではない。

意味は次である。

> 残りの block を lexical continuation として受け取り、その continuation を lambda body にする。

形式的には:

```text
desugar(param a: A; rest)
  =
fn[A] { a =>
  desugar(rest)
}
```

したがって:

- binder scope が字句的に見える。
- binder type がソースに現れる。
- remainder of block が lambda body になる。
- hidden goal list を消費しない。
- source structure から lambda nesting が決まる。
- generated lambda を表示できる。

---

## 10.2 Answer-type modification

`param` は普通の monadic action ではない。

通常の bind:

```text
Synth[X] flatMap (Term[X] => Synth[Y])
```

は result type `Y` を保つ。

一方 `param A` は、rest が作る `B` を全体として `A => B` に変換する。

```text
rest: Synth[B]
param A; rest: Synth[A => B]
```

これは answer-type modification を伴う scoped operation である。

この点を設計文書で明示すること。

---

## 10.3 Implementation choices

第一候補を、次の順で比較する。

### Choice A: Direct syntax desugaring

parser/elaborator が:

```scala
synth do {
  param a: A
  param b: B
  yield body
}
```

を直接:

```scala
fn[A] { a =>
  fn[B] { b =>
    use(body)
  }
}
```

へ変換する。

MVP の第一候補。

長所:

- 小さい。
- soundness boundary が明確。
- source map を保持しやすい。
- Scala/JVM 上の continuation runtime が不要。
- answer-type modification を host type system に無理に埋め込まなくてよい。

### Choice B: Free scoped algebra

概念的 GADT:

```scala
sealed trait Build[A]

final case class Pure[A](
  term: Term[A]
) extends Build[A]

final case class Bind[X, A](
  source: Synth[X],
  continuation: Term[X] => Build[A]
) extends Build[A]

final case class Param[X, A](
  domain: TypeRep[X],
  body: Local[X] => Build[A]
) extends Build[X => A]

final case class Choice[A](
  alternatives: Vector[Build[A]]
) extends Build[A]
```

Scala 実装では index を完全に保存できない箇所があるため、existential wrapper と kernel recheck を使ってよい。

長所:

- source provenance を AST node に保持できる。
- multi-shot search と相性がよい。
- actual JVM continuation に依存しない。
- interpreter を複数作れる。
- pretty printer / desugaring viewer を作りやすい。

### Choice C: Typed delimited continuation

`derive` / `synth` を delimiter とし、`param` を scoped continuation capture として扱う。

概念:

```text
derive / synth = reset-like delimiter
param A        = capture remainder of current block
```

採用する場合、answer-type modification をどう型付けするかを ADR に記録する。

---

## 10.4 Raw `call/cc`

MVP の public API に unrestricted `call/cc` を入れない。

理由:

- answer type が見えにくい。
- continuation escape が source structure を壊しうる。
- nondeterministic search と組み合わせると single-shot / multi-shot の選択が必要。
- generated term provenance が追いにくくなる。
- accidental hidden control state を作りやすい。
- diagnostics が難しくなる。

導入する場合は、隔離された experimental namespace に置く。

```text
tessera.meta.control.experimental
```

ADR で最低限、次を扱う。

- delimiter
- answer-type polymorphism
- answer-type modification
- continuation lifetime
- single-shot / multi-shot
- escape policy
- interaction with search
- interaction with failure
- interaction with source maps
- reproducibility
- generated-term reconstruction

---

## 10.5 Continuation safety invariants

- continuation は kernel term に残らない。
- continuation は meta layer のみ。
- captured continuation は enclosing `derive` / `synth` delimiter を越えない。
- invocation は最終的に `Term[A]` 生成へ戻る。
- continuation を使った結果も kernel recheck を受ける。
- continuation の導入で public API が `ProofState => ProofState` に退化しない。
- continuation combinator には generated term の説明が必要。
- captured continuation が mutable state を共有しない。
- nondeterministic search で再利用する場合は multi-shot safe または replayable である。

---

## 10.6 Multi-parameter `fn`

継続なしでも多くの nesting を減らせるように、n-ary sugar を提供する。

```scala
fn[A, B] { (a, b) =>
  use(And.intro(a, b))
}
```

desugars to:

```scala
fn[A] { a =>
  fn[B] { b =>
    use(And.intro(a, b))
  }
}
```

dependent binder:

```scala
depFn do {
  param x: A
  param y: B(x)
  yield body(x, y)
}
```

後続 binder type は先行 binder を参照できる。

---

# 11. Branching and scoped control

branch は普通の match または構造化 combinator とする。

```scala
meta def swapOr[A: Prop, B: Prop](
  value: Term[Or[A, B]]
): Synth[Or[B, A]] =
  inspect(value) {
    case Or.left(a) =>
      use(Or.right(a))

    case Or.right(b) =>
      use(Or.left(b))
  }
```

branch body に `synth do` を置ける。

```scala
inspect(value) {
  case Or.left(a) =>
    synth do {
      x <- transformLeft(a)
      yield Or.right(x)
    }

  case Or.right(b) =>
    synth do {
      y <- transformRight(b)
      yield Or.left(y)
    }
}
```

branch continuation は lexical scope を持つ。

「case 1 の後に case 2 のゴールが残る」という UI にしない。

---

# 12. Search, failure, choice, commit

推奨モデル:

```scala
enum Search[+A] {
  case success(value: A, trace: Trace)
  case failure(diagnostic: Diagnostic)
  case choice(alternatives: LazyList[Search[A]])
}
```

要件:

- pure value
- deterministic mode
- explicit budget
- structured failure tree
- reproducible
- kernel recheck
- no user-visible state restoration

`fail` は現在の search branch を終了する。

`choose` は複数 branch を作る。

`commit` / `cut` を提供する場合、明示 delimiter 内だけに作用する expression-valued combinator とする。

```scala
commit(
  choose(first, second)
)
```

命令的な global cut にしない。

nondeterministic search が continuation を複数回呼ぶなら、continuation は pure multi-shot value または再構築可能な Build AST とする。

---

# 13. `derive`

概念上:

```scala
def derive[A](source: Synth[A]): A
```

ただし runtime function ではない。

elaboration 時に:

1. expected type `A` を得る。
2. explicit `Goal[A]` を作る。
3. `Synth[A]` を評価する。
4. candidate `Term[A]` を得る。
5. source map を付ける。
6. unresolved metavariable がないことを確認する。
7. kernel で `A` に対して再検査する。
8. ordinary term として挿入する。

one-off sugar:

```scala
theorem pair[A: Prop, B: Prop]: A => B => And[A, B] =
  derive do {
    param a: A
    param b: B
    yield And.intro(a, b)
  }
```

これは proof mode ではなく、一個の expression である。

---

# 14. Reusable synth examples

明示 combinator:

```scala
meta def andIntroExplicit[A: Prop, B: Prop]
  : Synth[A => B => And[A, B]] =
  fn[A] { a =>
    fn[B] { b =>
      construct(And.intro)(
        left  = use(a),
        right = use(b)
      )
    }
  }
```

flat `do`:

```scala
meta def andIntroDo[A: Prop, B: Prop]
  : Synth[A => B => And[A, B]] =
  synth do {
    param a: A
    param b: B
    yield And.intro(a, b)
  }
```

context lookup:

```scala
meta def andFromContext[A: Prop, B: Prop]
  : Synth[And[A, B]] =
  synth do {
    a <- lookup[A]
    b <- lookup[B]
    yield And.intro(a, b)
  }
```

composition:

```scala
meta def composeSynth[A: Type, B: Type, C: Type](
  f: Term[A => B],
  g: Term[B => C]
): Synth[A => C] =
  synth do {
    param a: A
    b <- call(f, use(a))
    c <- call(g, use(b))
    yield c
  }
```

`#showTerm andIntroDo`:

```scala
(a: A) => (b: B) =>
  And.intro(a, b)
```

`andIntroExplicit` と `andIntroDo` は alpha-equivalent term を生成しなければならない。

---

# 15. Typed quotation and splicing

Phase 2 以降で typed quasiquotation を設計する。

```scala
code {
  And.intro($left, $right)
}
```

要件:

- hygienic
- typed
- binder-safe
- source span preserving
- splice type mismatch を generator source へ戻す
- generated code は再 elaboration / kernel check
- quotation が continuation や search state を kernel term へ漏らさない

MVP は AST constructor API のみでもよい。

---

# 16. Core IR

最低限:

```scala
enum CoreTerm {
  case sort(level: Level)
  case bound(index: Int)
  case const(name: QualifiedName, levels: Vector[Level])
  case pi(name: NameHint, domain: CoreTerm, codomain: CoreTerm)
  case lambda(name: NameHint, domain: CoreTerm, body: CoreTerm)
  case app(function: CoreTerm, argument: CoreTerm)
  case let(
    name: NameHint,
    valueType: CoreTerm,
    value: CoreTerm,
    body: CoreTerm
  )
  case constructor(ref: ConstructorRef, args: Vector[CoreTerm])
  case matchTerm(
    info: MatchInfo,
    scrutinee: CoreTerm,
    motive: CoreTerm,
    branches: Vector[Branch]
  )
}
```

kernel input type に metavariable を含めない。

elaboration 用:

```scala
enum ElabTerm {
  ...
  case meta(id: MetaVarId)
}
```

surface は named binder、core は de Bruijn index または level を第一候補とする。

substitution / shifting / alpha / pretty print の tests を必須とする。

---

# 17. Elaboration

bidirectional typing:

```text
infer(term) -> type
check(term, expectedType)
```

metavariable:

```scala
final case class MetaVar(
  id: MetaVarId,
  context: LocalContext,
  expectedType: CoreTerm,
  assignment: Option[CoreTerm],
  origin: Origin,
  sourceSpan: SourceSpan
)
```

要件:

- occurs check
- scope escape check
- universe constraints
- stuck constraints
- structured failure
- unresolved metavariable rejection

MVP unification は higher-order pattern fragment から始めてよい。

完全な higher-order unification を装わない。

---

## 17.1 `do` elaboration

`do` parser は statement の source span を保持する。

elaborator は block を syntax-directed に次へ変換する。

- monadic bind
- ordinary let
- scoped `param`
- final `use`
- explicit `guard`

`param` の expected result type を検査する。

例:

```scala
Synth[A => B => C]
```

に対して:

```text
param a: A
param b: B
yield c
```

は受理する。

expected type が function でない場合:

```text
`param a: A` would construct a function `A => ...`,
but the enclosing synthesis expects `C`
```

のように説明する。

---

# 18. Kernel

kernel package の責務:

- environment lookup
- universe checking
- type inference/checking for closed core terms
- definitional equality
- declaration validation
- positivity
- termination
- proof irrelevance if implemented

kernel は次を import しない。

- parser
- surface syntax
- elaborator
- meta
- search
- continuation
- IDE

dependency:

```text
surface/parser ─┐
elaborator     ─┼──> core
meta           ─┘

kernel ────────────> core
```

`kernel -> meta` を禁止する。

---

## 18.1 Reducibility

- `def`: transparent
- `opaque def`: opaque
- `theorem`: opaque by default
- local let: reducible

WHNF-based conversion から始めてよい。

NbE は ADR で比較する。

---

# 19. Scala 3 reference implementation

reference implementation は Scala 3。

理由:

- surface と近い。
- ADT/pattern matching が core に向く。
- immutable design に向く。
- Scala.js へ発展可能。

最初は single sbt module + package 分割。

```text
tessera.syntax
tessera.parser
tessera.core
tessera.kernel
tessera.elab
tessera.meta
tessera.pretty
tessera.cli
```

minimal dependencies。

- test: munit
- parser: handwritten lexer + Pratt/recursive descent、または小さい parser library

重い effect framework は MVP に入れない。

---

## 19.1 Meta implementation recommendation

MVP では次を第一候補とする。

1. `Synth` explicit combinator API を実装。
2. `for` を `map` / `flatMap` へ desugar。
3. `synth do` を Build AST または direct desugaring へ変換。
4. `param` を nested `fn` へ変換。
5. search は immutable `Search`。
6. generated term を kernel recheck。
7. source provenance を保持。

actual JVM continuation library を最初から導入しない。

continuation semantics が必要でも、まず free scoped algebra または direct desugaring で意味を固定する。

---

# 20. Diagnostics and provenance

origin:

```scala
enum Origin {
  case source(span: SourceSpan)
  case generatedBy(metaDecl: QualifiedName, span: SourceSpan)
  case doBinding(name: String, span: SourceSpan, parent: Origin)
  case paramBinder(name: String, span: SourceSpan, parent: Origin)
  case constructorField(name: String, parent: Origin)
  case synthesized(strategy: String, parent: Origin)
}
```

type mismatch example:

```text
cannot synthesize binding `b`

statement:
  b <- lookup[B]

context:
  A : Prop
  B : Prop
  a : A

expected:
  B

tried:
  lookup[B]

result:
  no local value has type B
```

`param` mismatch:

```text
cannot introduce parameter `x: A`

the enclosing synthesis expects:
  C

this statement would produce:
  A => ...

desugared form:
  fn[A] { x => ... }
```

generated-term rejection:

```text
generated term rejected by kernel

generated:
  And.intro(a, a)

kernel error:
  field `right` expected B, found A

generator:
  andIntroDo

do statement:
  yield And.intro(a, a)
```

---

# 21. CLI

最低限:

```text
tessera check file.tes
tessera eval file.tes expression
tessera holes file.tes
tessera show-term file.tes declaration
tessera show-core file.tes declaration
tessera show-synth file.tes declaration
tessera show-desugared file.tes declaration
tessera trace-synth file.tes declaration
```

README に実際に動く sbt command を載せる。

---

# 22. Documentation deliverables

```text
README.md
AGENTS.md
IMPLEMENTATION_STATUS.md

docs/
  VISION.md
  LANGUAGE.md
  CORE_CALCULUS.md
  ELABORATION.md
  META_LANGUAGE.md
  DO_NOTATION.md
  CONTINUATIONS.md
  KERNEL.md
  DIAGNOSTICS.md
  ROADMAP.md
  adr/
```

---

## 22.1 `DO_NOTATION.md`

最低限:

- grammar
- `for` desugaring
- `do` desugaring
- final `yield`
- ordinary `let`
- pattern binding
- failure semantics
- applicative `zip`
- source maps
- explicit examples
- desugared examples
- restrictions

---

## 22.2 `CONTINUATIONS.md`

最低限:

- why monad alone does not flatten `fn`
- `param` and answer-type modification
- direct desugaring
- free scoped algebra
- typed delimited continuation
- raw `call/cc` anti-goal
- single-shot vs multi-shot
- nondeterministic search
- delimiter escape policy
- source provenance
- generated-term reconstruction
- chosen MVP strategy

---

## 22.3 `AGENTS.md`

最低限の invariants:

1. kernel は parser/elab/meta/search/control に依存しない。
2. unresolved metavariable を kernel に渡さない。
3. public meta API に mutable global proof state を導入しない。
4. public meta API を `List[Goal] => List[Goal]` にしない。
5. 新しい Synth combinator には型・generated term・failure test・composition test が必要。
6. binder/substitution/unification bug には regression test を追加する。
7. generated term を表示できない automation を追加しない。
8. implementation status を誇張しない。
9. `for` / `do` は既存 combinator へ desugar する。
10. `do` 専用 mutable proof-state interpreter を作らない。
11. `param` は lexical scoped binder として実装する。
12. `param` を goal-list の先頭を消費する command にしない。
13. continuation は `derive` delimiter を越えて escape させない。
14. raw `call/cc` 追加には ADR を必須とする。
15. source map を desugaring で失わない。
16. independent slot は可能なら applicative に合成する。
17. continuation が mutable captured state を共有しない。
18. search で continuation を再利用する場合、multi-shot safety を保証する。
19. feature は generated term が kernel check を通るまで done としない。
20. architecture invariant を CI test で検証する。

---

# 23. Required examples

```text
examples/Identity.tes
examples/Functions.tes
examples/AndOr.tes
examples/Nat.tes
examples/Equality.tes
examples/Holes.tes
examples/SynthesisExplicit.tes
examples/SynthesisFor.tes
examples/SynthesisDo.tes
examples/SynthesisParam.tes
examples/SynthesisFailure.tes
examples/SynthesisBranching.tes
```

最低限:

- term-only identity
- composition
- And intro
- And commutativity
- Or elimination
- Nat add
- addZeroRight
- transport
- explicit Synth
- `for` Synth
- `do` Synth
- `param`
- dependent `param`
- applicative zip
- branch-local do
- failure trace
- generated term inspection

README では、次の三つを横並びにする。

ordinary term:

```scala
theorem andIntro[A: Prop, B: Prop]: A => B => And[A, B] =
  (a: A) => (b: B) =>
    And.intro(a, b)
```

explicit Synth:

```scala
meta def andIntroExplicit[A: Prop, B: Prop]
  : Synth[A => B => And[A, B]] =
  fn[A] { a =>
    fn[B] { b =>
      use(And.intro(a, b))
    }
  }
```

flat Synth:

```scala
meta def andIntroDo[A: Prop, B: Prop]
  : Synth[A => B => And[A, B]] =
  synth do {
    param a: A
    param b: B
    yield And.intro(a, b)
  }
```

三つが同じ ordinary term へ到達することを示す。

---

# 24. MVP phases

## Phase 0: Design freeze

作るもの:

- VISION
- LANGUAGE
- CORE_CALCULUS
- META_LANGUAGE
- DO_NOTATION
- CONTINUATIONS
- initial ADRs
- IMPLEMENTATION_STATUS

設計だけを延々続けず、vertical slice へ進む。

---

## Phase 1: Minimal kernel

- sorts/universes
- variables
- Pi
- lambda
- app
- let
- constants
- type checking
- beta/delta/zeta
- de Bruijn
- pretty printer
- tests

手組み `id` term を受理し、不正 term を拒否する。

---

## Phase 2: Surface + elaboration

- lexer/parser
- source spans
- names
- `def`
- `theorem`
- function type
- lambda
- application
- annotations
- holes
- bidirectional typing
- metavariables
- basic unification
- CLI check/holes

---

## Phase 3: Basic data/equality

最初は built-in でもよい。

- Unit
- Empty
- Product/And
- Sum/Or
- Nat
- Eq
- match
- recursor
- refl
- transport
- structural recursion

built-in と general inductive を混同しない。

---

## Phase 4: Explicit compositional synthesis

- `Term`
- `Goal`
- `Synth`
- `Search`
- `Refinement`
- `use`
- `fn`
- `call`
- `construct`
- `inspect`
- `lookup`
- `choose`
- `map`
- `flatMap`
- `zip`
- `label`
- `derive`
- trace
- generated term display

---

## Phase 5: `for` / `do` / scoped `param`

- Scala `for`
- Haskell-like `do`
- `yield`
- ordinary let
- pattern bind
- `param`
- n-ary `fn`
- direct desugaring or Build AST
- `#showDesugared`
- source provenance
- delimiter safety
- equivalence tests

MVP では raw continuation API を公開しない。

---

## Phase 6: General inductives

- declaration checking
- positivity
- eliminator generation
- dependent matching
- structural recursion
- indexed families
- Vec

---

## Phase 7: Tooling

- better pretty printer
- LSP
- term-inserting code actions
- hole explorer
- Scala.js
- Web Playground

MVP 完了条件には含めない。

---

# 25. Acceptance criteria

## Build

- `sbt test` passes
- CLI starts
- README commands work

## Kernel

- valid identity accepted
- type mismatch rejected
- scope escape rejected
- unresolved metavariable rejected
- malformed universe usage rejected
- opaque theorem not accidentally unfolded

## Surface

- lambda/application
- named hole
- expected type/context
- correct source span

## Term proofs

- andIntro
- andComm
- orElim
- addZeroRight
- transport

## Synthesis

- Synth can be a value
- Synth can be argument/return value
- explicit nested `fn`
- named construct fields
- reusable lookup/search
- generated term display
- kernel recheck
- structured failure tree

## `for` / `do`

- `for` and explicit flatMap generate alpha-equivalent terms
- `do` and explicit flatMap generate alpha-equivalent terms
- `param` and nested `fn` generate alpha-equivalent lambdas
- nested do is reusable
- failure points to original statement
- `#showDesugared` works
- no mutable goal stack
- final yield type is checked

## Continuation/scoped control

- delimiter escape is rejected
- search replay does not observe mutated captured state
- multi-shot behavior is tested if supported
- continuation does not appear in core
- generated term is independently checked
- raw `call/cc` absent from stable public API

## Architecture

- kernel has no meta/elab/parser/control dependency
- core has no surface dependency
- public meta API has no goal-list transformer
- architecture check exists in tests or build

---

# 26. Tests

unit:

- lexer/parser
- precedence
- substitution
- shifting
- alpha equivalence
- WHNF
- defeq
- universes
- type checking
- unification
- holes
- each Synth combinator
- `for` desugaring
- `do` desugaring
- `param` desugaring
- n-ary `fn`
- zip/applicative
- source map through sugar
- delimiter safety
- search replay

property tests where practical:

- substitution preserves closedness
- shift/unshift round-trip
- defeq reflexivity
- normalization idempotence for supported fragment
- parse/print round-trip
- successful Synth[A] produces term checking as A
- explicit/for/do forms are alpha-equivalent
- `param` form equals nested `fn`

soundness-related bugには regression test を必須とする。

---

# 27. Required ADRs

1. Core universe model
2. `Prop`
3. de Bruijn index vs level
4. WHNF vs NbE
5. equality representation
6. inductive strategy
7. termination
8. positivity
9. implicit arguments
10. surface layout
11. Synth runtime representation
12. Search representation
13. Refinement representation
14. typed quotation
15. generated-term source map
16. reducibility
17. `for` desugaring
18. `do` desugaring
19. scoped `param`
20. answer-type modification
21. direct desugaring vs free scoped algebra
22. delimited continuation model
23. raw `call/cc` exposure policy
24. single-shot vs multi-shot
25. applicative vs monadic field synthesis
26. error accumulation

些細な選択のたびにユーザーへ質問して止まらない。

最も単純で sound な選択を行い、ADR に記録する。

---

# 28. Anti-goals

初期段階では狙わない。

- Lean compatibility
- Coq compatibility
- tactic script migration
- huge math library
- SMT integration
- tactic golf
- Unicode mandatory syntax
- unrestricted general recursion in kernel
- giant macro system
- LSP-first development
- Web-first development
- parser convenience at kernel soundness cost
- unrestricted public `call/cc`
- `do` の名の下での tactic-style goal stack
- opaque control operation with no desugaring
- automation whose generated term cannot be shown
- “existing provers do it” as sole justification

---

# 29. Work discipline

1. existing repository を最初に調査する。
2. user files を不用意に上書きしない。
3. empty repository なら minimal sbt project。
4. docs を固定してから vertical slice。
5. phase ごとに tests。
6. pseudocode だけで終わらない。
7. fake implementation をしない。
8. implemented/planned を分ける。
9. generated term + kernel recheck を最優先。
10. meta convenience より compositionality。
11. nesting reduction と hidden-state elimination を両立させる。
12. error messages を後回しにしない。
13. `IMPLEMENTATION_STATUS.md` を更新する。
14. raw continuation を導入する前に direct desugaring で必要性を検証する。

---

# 30. Codex の作業順序

## Step 1: Inspect

- files
- build
- code
- tests
- constraints
- license

## Step 2: Design

- core
- surface
- Synth
- do
- param
- continuation policy
- ADRs

## Step 3: Kernel vertical slice

```text
hand-built core term
  -> kernel check
```

## Step 4: Source vertical slice

```text
source
  -> parse
  -> elaborate
  -> closed core
  -> kernel
```

## Step 5: Hole vertical slice

```text
source hole
  -> expected type/context/origin
```

## Step 6: Explicit Synth vertical slice

```text
derive(Synth[A])
  -> Term[A]
  -> kernel
  -> show term
```

## Step 7: `for` / `do` / `param`

```text
surface sugar
  -> desugared Synth
  -> generated term
  -> kernel
```

## Step 8: Verify

- tests
- examples
- CLI
- architecture
- docs
- status

---

# 31. Final report

作業終了時、次を報告する。

1. implemented
2. not implemented
3. design decisions
4. file tree
5. commands
6. test results
7. ordinary term example
8. explicit Synth example
9. `for` example
10. `do` + `param` example
11. desugared form
12. generated ordinary term
13. continuation strategy
14. next smallest step

「完成した」だけで済ませない。

---

# 32. 最終設計判定基準

新機能ごとに問う。

1. ordinary dependent program として見えるか。
2. data flow が syntax に出ているか。
3. subpart は named/structured か。
4. hidden current goal に依存していないか。
5. normal function として合成できるか。
6. nesting を必要以上に強制していないか。
7. sugar は explicit combinator へ展開できるか。
8. scoped control の delimiter は明白か。
9. generated term を表示できるか。
10. kernel が独立に検査できるか。
11. failure を元 syntax へ戻せるか。
12. continuation が goal stack の偽装になっていないか。
13. independent subproof に無意味な順序を入れていないか。
14. 「既存系がそうだから」以外の理由があるか。

重大に満たさないなら採用しない。

---

# 33. README 冒頭の宣言

README の冒頭に、次の趣旨を掲げる。

> Tessera では、証明はスクリプトではない。  
> 証明は依存型を持つ普通のプログラムである。  
> 自動化は見えないゴールスタックを操作しない。  
> 自動化は型付きゴールから検査可能な項を返す。  
> `for` と `do` は合成を読みやすくするが、意味を隠さない。  
> `param` は continuation を通じて lambda を作るが、proof state を変更しない。  
> 最後に kernel が見るのは、ただの ordinary core term である。

この思想を、syntax、types、implementation、errors、IDE、tutorial のすべてで一貫させよ。
