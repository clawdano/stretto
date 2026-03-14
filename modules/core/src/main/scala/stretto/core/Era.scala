package stretto.core

enum Era:
  case Byron, Shelley, Allegra, Mary, Alonzo, Babbage, Conway

object Era:
  given Ordering[Era] = Ordering.by(_.ordinal)
