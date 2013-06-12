\*                                                   

**********************************************************************************
*                           The License						*
* 										*
* The user is free to produce commercial applications with the software, to 	*
* distribute these applications in source or binary  form, and to charge monies *
* for them as he sees fit and in concordance with the laws of the land subject 	*
* to the following license.							*
*										* 
* 1. The license applies to all the software and all derived software and 	*
*    must appear on such.							*
*										*
* 2. It is illegal to distribute the software without this license attached	*
*    to it and use of the software implies agreement with the license as such.  *
*    It is illegal for anyone who is not the copyright holder to tamper with 	*
*    or change the license.							*
*										*
* 3. Neither the names of Lambda Associates or the copyright holder may be used *
*    to endorse or promote products built using the software without specific 	*
*    prior written permission from the copyright holder.			*
*										*
* 4. That possession of this license does not confer on the copyright holder 	*
*    any special contractual obligation towards the user. That in no event 	* 
*    shall the copyright holder be liable for any direct, indirect, incidental, *   
*    special, exemplary or consequential damages (including but not limited     *
*    to procurement of substitute goods or services, loss of use, data, 	* 
*    interruption), however caused and on any theory of liability, whether in	* 
*    contract, strict liability or tort (including negligence) arising in any 	*
*    way out of the use of the software, even if advised of the possibility of 	*
*    such damage.								* 
*										*
* 5. It is permitted for the user to change the software, for the purpose of 	*
*    improving performance, correcting an error, or porting to a new platform, 	*
*    and distribute the derived version of Shen provided the resulting program 	*
*    conforms in all respects to the Shen standard and is issued under that     * 
*    title. The user must make it clear with his distribution that he/she is 	*
*    the author of the changes and what these changes are and why. 		*
*										*
* 6. Derived versions of this software in whatever form are subject to the same *
*    restrictions. In particular it is not permitted to make derived copies of  *
*    this software which do not conform to the Shen standard or appear under a  *
*    different title.								*
*										*
*    It is permitted to distribute versions of Shen which incorporate libraries,*
*    graphics or other facilities which are not part of the Shen standard.	*
*										*
* For an explication of this license see www.shenlanguage.org/license.htm which *
* explains this license in full. 
*				 						*
*********************************************************************************

*\

(package shen. []

(define yacc
  [defcc S { A ==> B } | CC_Stuff] -> (yacc [defcc S | CC_Stuff]) 
  [defcc S | CC_Stuff] -> (yacc->shen S CC_Stuff))

(define yacc->shen
  S CC_Stuff -> (let CCRules (split_cc_rules CC_Stuff [])
                     CCBody (map (function cc_body) CCRules)
                     YaccCases (yacc_cases CCBody)
                     CatchKill (catchkill YaccCases)
                     [define S (protect Stream) -> CatchKill]))
                     
(define split_cc_rules
  [] [] -> []
  [] RevRule -> [(split_cc_rule (reverse RevRule) [])]
  [; | CC_Stuff] RevRule 
   -> [(split_cc_rule (reverse RevRule) []) | (split_cc_rules CC_Stuff [])]
  [X | CC_Stuff] RevRule -> (split_cc_rules CC_Stuff [X | RevRule]))
  
(define split_cc_rule 
   [:= Semantics] RevSyntax -> [(reverse RevSyntax) Semantics]   	
   [:= Semantics where Guard] RevSyntax 				
    -> [(reverse RevSyntax) [where Guard Semantics]]
   [] RevSyntax 
   -> (do (output "warning: ")
          (map (/. X (output "~A " X)) (reverse RevSyntax))
          (output "has no semantics.~%")
          (split_cc_rule [:= (default_semantics (reverse RevSyntax))] RevSyntax))
   [Syntax | Rule] RevSyntax -> (split_cc_rule Rule [Syntax | RevSyntax])) 
   
(define default_semantics 
  [] -> []
  [S | Syntax] -> [append S (default_semantics Syntax)]	  where (grammar_symbol? S)
  [S | Syntax] -> [cons S (default_semantics Syntax)]) 
  
(define grammar_symbol?
  S -> (and (symbol? S) 
            (let Cs (strip-pathname (explode S)) 
                (and (= (hd Cs) "<") (= (hd (reverse Cs)) ">")))))                          

(define yacc_cases
  [Case] -> Case
  [Case | Cases] -> (let P (protect YaccParse)
                       [let P Case
                         [if [= P [fail]]
                             (yacc_cases Cases)
                             P]]))

(define cc_body 
  [Syntax Semantics] -> (syntax Syntax (protect Stream) Semantics))

(define syntax 
  [] Stream [where Guard Semantics] -> [if (semantics Guard) 
                                           [pair [hd Stream] (semantics Semantics)]
                                           [fail]]
  [] Stream Semantics -> [pair [hd Stream] (semantics Semantics)]
  [S | Syntax] Stream Semantics 
    -> (cases (grammar_symbol? S) (recursive_descent [S | Syntax] Stream Semantics)
              (variable? S) (variable-match [S | Syntax] Stream Semantics)
              (jump_stream? S) (jump_stream [S | Syntax] Stream Semantics)
              (terminal? S) (check_stream [S | Syntax] Stream Semantics)              
              (list_stream? S) (list_stream (decons S) Syntax Stream Semantics)
	            true (error "~A is not legal syntax~%" S)))       

(define list_stream?
  [_ | _] -> true
  _ -> false)

(define decons
  [cons X Y] -> [X | (decons Y)]
  X -> X)

(define list_stream
  S Syntax Stream Semantics 
   -> (let Test [and [cons? [hd Stream]] [cons? [hd [hd Stream]]]]
           Action [snd-or-fail (syntax S 
                          [pair [hd [hd Stream]] [hdtl Stream]]
                          [leave! (syntax Syntax 
                              [pair [tl [hd Stream]]
                                          [hdtl Stream]]
                              Semantics)])] 
          Else [fail]
          [if Test Action Else])) 
          
(define snd-or-fail
  [_ Y] -> Y
  _ -> (fail))          
   
(define strip-pathname
  Cs -> Cs 		where (not (element? "." Cs))
  [_ | Cs] -> (strip-pathname Cs))				
  
(define recursive_descent 
  [S | Syntax] Stream Semantics -> (let Test [S Stream]
                                        Action (syntax Syntax 
                                                       (concat (protect Parse_) S) Semantics)
                                        Else [fail]
                                        [let (concat (protect Parse_) S) Test
                                             [if [not [= [fail] (concat (protect Parse_) S)]]
                                                 Action
                                                 Else]])) 

(define variable-match 
  [S | Syntax] Stream Semantics 
   -> (let Test [cons? [hd Stream]]
           Action [let (concat (protect Parse_) S) [hd [hd Stream]]
                   (syntax Syntax [pair [tl [hd Stream]] 
                                             [hdtl Stream]] Semantics)]
           Else [fail]
           [if Test Action Else])) 
         
(define terminal? 
  [_ | _] -> false
  X -> false  where (variable? X)
  _ -> true)

(define jump_stream?
   X -> true  where (= X _)
   _ -> false)
  
(define check_stream 
  [S | Syntax] Stream Semantics 
  -> (let Test [and [cons? [hd Stream]] [= S [hd [hd Stream]]]]
          Action (syntax Syntax [pair [tl [hd Stream]] 
                                            [hdtl Stream]] Semantics)
          Else [fail]
          [if Test Action Else])) 

(define jump_stream 
  [S | Syntax] Stream Semantics 
  -> (let Test [cons? [hd Stream]]
          Action (syntax Syntax [pair [tl [hd Stream]] 
                                            [hdtl Stream]] Semantics)
          Else [fail]
          [if Test Action Else]))
  
(define semantics
  [leave! S] -> S
  [] -> []
  S -> [hdtl (concat (protect Parse_) S)] 	where (grammar_symbol? S) 
  S -> (concat (protect Parse_) S) 	where (variable? S)
  [X | Y] -> (map (function semantics) [X | Y])
  X -> X)       

(define fail
  -> fail!) 
  
(define pair
  X Y -> [X Y])
 
(define hdtl
  X -> (hd (tl X)))  

(define <!>
  [X _] -> [[] X]
  _ -> (fail)) 

(define <e>
  [X _] -> [X []])
  
(define catchkill
  Code -> [trap-error Code [lambda (protect E) [analyse-kill (protect E)]]])
  
(define analyse-kill
  Exception -> (let String (error-to-string Exception)
                    (if (= String "Shen YACC kill")
                        (fail)
                        (simple-error String))))
                        
(define kill
  -> (simple-error "Shen YACC kill"))                            

)