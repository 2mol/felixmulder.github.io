---
title: "Haskell in Production: Designing Testable Components"
date: 2019-10-05
published: true
---

# Designing Testable Components
This chapter of the "Haskell in Production" article series focuses on how to
write components that result in testable components. This chapter will give you
the tools that are analogous to OOP mocking and dependency injection.

You can find the complete code for this service in the
[haskell-in-production](https://github.com/felixmulder/haskell-in-production)
repo on GitHub.

## Let's build an HTTP API!
In this tutorial we're going to be building an HTTP API. The API will have two
endpoints:

* Create a user `POST /user`
  ```json
  {
    "username": "<String>",
    "password": "<String>"
  }
  ```
* Delete a user `DELETE /user/<user-id>`

## API definition
We're going to use a simplified model of a Haskell API - but we could easily
use something like Servant (which is what use at Klarna by the way).

```haskell
api :: Request -> IO Response
api request =
  case methodAndPath request of
    POST (matches "/user" -> Just []) -> do
      createNewUser (requestBody request) >>= toResponse
    DELETE (matches "/user/:userId" -> Just [userId]) ->
      deleteUserId (UserId userId) >>= toResponse
    _unmatched ->
      pure NoResponse

main :: IO ()
main = run "8080" api
```

## Dependency Injection
If you come from a Java or other OOP languages, then you've surely dealt with
dependency injection via annotations or frameworks like Guice or beans.

But what is really dependency injection? Let's get back to the core of it. DI
is simply parameterizing components. The simplest form of dependency injection
is just passing the dependencies as arguments to functions.

## Parameterizing Functions
```haskell
createNewUser :: RequestBody -> IO (Either Error User)
createNewUser body =
  case bodyToUser body of
    Left err -> pure . Left $ err
    Right (user, pass) -> do
      -- Connect to DB:
      db <- connectToDb
      let
        insertSql =
          "INSERT INTO table (user_name, password) VALUES (?, ?) returning id"

      -- Persist using insert statement:
      userId <- query db insertSql (user, pass)

      -- Create a response from the persisted argument:
      pure . Right $ User { userName = user, userId = userId }
```

This function is not very clean for a number of reasons:

* It seems to connect to the DB on every call
* It doesn't take any configuration in order to know how to connect to the DB
* The DB persistence is not abstracted from the domain logic

Let's solve this by parameterizing the function - with another function!

```haskell
insertNewUser :: Database -> UserName -> Password -> IO UserId
insertNewUser db user pass =
  let
    insertSql =
      "INSERT INTO table (user_name, password) VALUES (?, ?) returning id"
  in
    query db insertSql (user, pass)

createNewUser ::
     (UserName -> Password -> IO UserId)
  -> RequestBody
  -> IO (Either Error User)
createNewUser persistUser body =
  case bodyToUser body of
    Left err -> pure . Left $ err
    Right (user, pass) -> do
      -- Persist user:
      userId <- persistUser user pass

      -- Create a response from the persisted argument:
      pure . Right $ User { userName = user, userId = userId }
```

From an approachability standpoint, it is now quite easy to understand how this
works and how to use the function.

When calling `createNewUser` we now simply provide the `insertNewUser` function
partially applied with a database.

## Solving the problem at scale
So here's the problem with the above solution: while it does work, it doesn't
really scale. Domain logic will often need access to several interfaces to do
its job. It might need both an HTTP client for some request and a database to
store the result. As the requirements grow, the solution above quickly becomes
quite verbose in practice.

E.g:

```haskell
validateUser :: (UserId -> IO (Maybe User)) -> (UserName -> IO Bool) -> UserId -> IO Bool
validateUser getUser validateUsername userId = do
  userM <- getUser userId
  maybe (pure False) (validateUsername . userName) userM
```

This example adds one function as argument, but what if you add a third? A
fourth? You get the picture.

We also don't want to write our code in `IO` - while useful of course, the
surface area of possible effects is huge. From an effect perspective, we'd like
to limit the power of each component. We'll get to this later in the article,
but first let's focus on solving scalability of this initial approach.

### Introducing the Handle pattern
Instead of parameterizing the function with another function we can
parameterize with a datatype containing a function. This is great for a number
of reasons.

1. We can group functions that operate in similar ways together (think OO
   interface)
2. We don't have to pass around all those functions, instead we pass one
   datatype

So how would this look?

```haskell
data Application =
  { persistUser :: UserName -> Password -> IO UserId
  , getUser :: UserId -> IO (Maybe User)
  , validateUsername :: UserId -> IO Bool
  , logLn :: Loggable a => a -> IO ()
  }
```

Now we can simply pass that to `validateUser`:

```haskell
validateUser :: Application -> RequestBody -> UserId -> IO Bool
validateUser app requestBody userId = do
  userM <- (app & getUser) userId
  maybe (pure False) (app & validateUsername $ userName) userM
```

But - when looking at this, you've probably seen an issue. `validateUsername`
does not really fit in with the rest of the functions in `Application`. As a
solution, we could nest the `Application` type. So let's redefine it:

```haskell
data Persistence =
  { persistUser :: UserName -> Password -> IO UserId
  , getUser :: UserId -> IO (Maybe User)
  }

data Application =
  { persistence :: Persistence
  , validateUsername :: UserId -> IO Bool
  }
```

This gives us a bit more granularity, and a cleaner interface to work with.
However, we still have a couple of issues:

* We're running in `IO`
* We have to manually pass around the `Application` everywhere it's needed
* We are unconstrained in what a function can do - if it receives application,
  it can do anything contained within that interface

## Getting rid of `IO`
If we add a generic type parameter to the handles, we can abstract away `IO`:

```haskell
data Persistence m =
  { persistUser :: UserName -> Password -> m UserId
  , getUser :: UserId -> m (Maybe User)
  }

data Application m =
  { persistence :: Persistence m
  , validateUsername :: UserId -> m Bool
  }
```

Now, if we wanted to - we can actually run these as pure functions by using the
`Identity` monad.

This might be strange to you, don't worry we'll, get to this in the testing
section below.

## Constraining Functions
When we write applications, typically the most powerful function will be
`main`. It can do anything. When it comes to our interfaces, we want to
constrain their possible effects - and thus limit what we need to test.

We're interested in what is commonly referred to as *effect tracking*.

Because we have gotten rid of `IO` in our refactoring above we can now choose
which monad to evaluate our programs in. This gives us the power to limit the
effects of the monad. We can evaluate it purely or we can evaluate it with
only certain effects.

There are many ways to do this in Haskell,
[fused-effects](http://hackage.haskell.org/package/fused-effects),
[extensible-effects](http://hackage.haskell.org/package/extensible-effects),
[polysemy](http://hackage.haskell.org/package/polysemy),
free monads, tagless final or the classic
[MTL](http://hackage.haskell.org/package/mtl) approach.

These libraries all exist for a single reasons - monads don't compose. You
can't take two different monads (e.g. `Maybe` and `IO`) and compose them.
The original solution to this is monad transformers (as found in the MTL lib).

When choosing something - consider choosing the easiest thing you can possibly
get away with. A friend once told me: "Whenever you write something, you should
aim to write it in a non-clever way. Because, when you have to debug it - you're
going to need to be twice as smart."

We're going to go with a solution that has a slightly higher degree of
boilerplate, which we're willing to do since it is more straight-forward.

The first order of business at this point is to introduce you to a monad called
"Reader". If you already know about it - you can skip ahead to [Actually
getting rid of the manual wiring](#actually-getting-rid-of-the-manual-wiring).

## Introducing reader
We want to get rid of the manual wiring. This is were Reader comes into play.
The easiest way to describe reader is to say that it is a monad that is able to
read a value from its context.

What's an example of something that can read a value from its context? Well, a
function!

```haskell
getPersistUser :: Application m -> (UserName -> Password -> m UserId)
getPersistUser app = app & persistence & persistUser
```

We could re-write this in a way where we don't explicitly have to pass the
`Application` argument:

```haskell
getPersistUser :: MonadReader Application m => m (UserName -> Password -> m UserId)
getPersistUser = do
  app <- ask
  pure $ app & persistence & persistUser
```

Unfortunately, the type signature has changed - but that is a very small price
to pay since we can just unwrap it by doing something like:

```haskell
runReader getPersistUser app
```

(In fact this will make the `m` be `Identity` and then unwrap it for us!)

We could of course run this in any monad we wanted to - like `Either a` or
`Maybe` or `IO`. It might seem like a contrived example, but bear in mind that
if we let all the functions that require this parameter be readers - we can
compose them before actually running it and thus only pass the parameter once.

## Actually getting rid of the manual wiring
Now that we know about reader, it's time to deliver on our goals of effect
tracking - and as an added bonus get cleaner interfaces for these effects.

We will now be using a type class in order to bundle things that fall under the
same effect. For instance writing and reading to the database would fall under
an interface `Persist`:

```haskell
class Monad m => Persist m where
  persistUser :: UserName -> Password -> m UserId
  getUser :: UserId -> m (Maybe User)
```

We're saying that `m` must be a monad, this will come in handy since it lets us
use do-notation.

This typeclass now allows us to re-write a function like `createNewUser` with a
type signature that let's us know about its effects.

```haskell
createNewUser :: Persist m => RequestBody -> m (Either Error User)
createNewUser body =
  case bodyToUser body of
    Left err -> pure . Left $ err
    Right (user, pass) -> do
      userId <- persistUser user pass
      -- Create a response from the persisted argument:
      pure . Right $ User { userName = user, userId = userId }
```

Notice how we now don't have to pass the `Application` to this function
anymore! It's pretty cool. Unfortunately, this means that we have to pay the
price somewhere else. We still need a concrete version of this to be able to
call it from `main`.

Let's create such an instance:

```haskell
instance
  ( MonadReader (Persistence m) m
  ) => Persist m where
  persistUser user pass =
    ask >>= \(Persistence persist _) -> persist user pass
  getUser userId =
    ask >>= \(Persistence _ get) -> get user pass
```

We're still not at the steady state solution here. Because, when we want to
compose different interfaces together - these instances don't have the same
reader. This one reads `Persistence m` and no other data. When we do
`runReader`, we have to do something like:

```haskell
runReader (persistUser "user" "pass") (app & persistence)
```

We cannot do:

```haskell
runReader (persistUser "user" "pass") app
```

Bummer.

But hey! We can solve this. We can make use of a type class
[`Has`](http://hackage.haskell.org/package/data-has) that simply tells us that
a datatype `r` has `a` by constraining the instance with "`Has a r`". After
refactoring, we get this:

```haskell
instance
  ( Has (Persistence m) r
  , Monad m
  ) => Persist (ReaderT r m) where
  persistUser user pass =
    asks getter >>= \(Persistence persist _) -> lift $ persist user pass
  getUser userId =
    asks getter >>= \(Persistence _ get) -> lift $ get user pass
```

Here we choose to create an instance for `ReaderT r m` which itself is a
reader. In fact, it's a reader that reads `r` from a specific monad `m`.

The great thing about this is that your interfaces compose under the same
monad. No need to, as in MTL, define the `n^2` number of instances where `n` is
the number of interfaces.

If we have a different interface:

```haskell
class Monad m => Log m where
  logLn :: HasCallStack => Loggable a => a -> m ()

data Logger m =
  Logger (Text -> m ())

instance
  ( Has (Logger m) r
  , Monad m
  ) => Log (ReaderT r m) where
  logLn a =
    asks getter >>= \(Logger doLog) -> lift . doLog . fromLoggable $ a
```

We can constrain our function:

```haskell
createNewUser ::
     Persist m
  => Log m
  => RequestBody -> m (Either Error User)
createNewUser body =
  case bodyToUser body of
    Left err ->
      Left err <$ logLn ("Couldn't convert " <> body <> "to user and pass")
    Right (user, pass) -> do
      logLn $ "Going to create " <> user
      userId <- persistUser user pass
      -- Create a response from the persisted argument:
      pure . Right $ User { userName = user, userId = userId }
```

and we can simply run it as:

```haskell
runReaderT (createNewUser request) (logger, persistence)
```

Or if we adjust `Application`:
```haskell
data Application m = Application
  { persistence :: Persistence m
  , logger :: Logger m
  }

app :: Application
app = _

runReaderT (createNewUser request) app
```

As you can see we're using `runReaderT` here instead of `runReader`. This is
because now we're not assuming that the effect is `Identity` - it can be any
monad `m`.

In summary, we can now say that each interface becomes a "capability" that the
function has. In the case of `createNewUser` it can perform pure computations
as well as both log and persist. This means that we have some semblance of
effect tracking. We're also able to organize our effects so that the most
powerful function is the entry point to the system (e.g. `main`) and then each
function performing domain logic becomes less powerful.

## The final application
We can now parameterize our `api` function from [API
definition](#api-definition) with these interfaces:

```haskell
api ::
    Log m
 => Persist m
 => Request -> m Response
api = _

main' :: Application IO -> IO ()
main' app = run "8080" $ \req -> runReaderT (api req) app

main :: IO ()
main = main' app
  where
    app :: Application IO
    app = Application
      { persist = defaultPersist
      , logger = defaultLogger
      }
```

In a real world application, we would also read the configuration from the
environment.

## Summary
In this section we've seen how to properly parameterize our interfaces and
instantiate them using `runReaderT`. We've set ourselves up to be able to test
these components individually and together. In the next section of this series,
we'll see just how to do that.

Next part [Testing your components](/writing/2019/10/05/Testing-your-components.html)