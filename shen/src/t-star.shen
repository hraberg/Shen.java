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

(define typecheck
  X A -> (let Curry (curry X) 
              ProcessN (start-new-prolog-process)
              Type (insert-prolog-variables (demodulate (curry-type A)) ProcessN)
              Continuation (freeze (return Type ProcessN void))
              (t* [Curry : Type] [] ProcessN Continuation)))
            
(define curry
  [F | X] -> [F | (map (function curry) X)]   where (special? F)
  [Def F | X] -> [Def F | X] where (extraspecial? Def)
  [F X Y | Z] -> (curry [[F X] Y | Z])
  [F X] -> [(curry F) (curry X)]
  X -> X)  

(define special?
  F -> (element? F (value *special*)))

(define extraspecial?
  F -> (element? F (value *extraspecial*)))
               
(defprolog t* 
  _ _ <-- (fwhen (maxinfexceeded?)) (bind Error (errormaxinfs));
  (mode fail -) _ <-- ! (prolog-failure);
  (mode [X : A] -) Hyp <-- (fwhen (type-theory-enabled?)) ! (th* X A Hyp);
  P Hyp <-- (show P Hyp) (bind Datatypes (value *datatypes*)) (udefs* P Hyp Datatypes);) 

(define type-theory-enabled?
  -> (value *shen-type-theory-enabled?*)) 

(define enable-type-theory
  + -> (set *shen-type-theory-enabled?* true)
  - -> (set *shen-type-theory-enabled?* false) 
  _ -> (error "enable-type-theory expects a + or a -~%"))

(define prolog-failure
  _ _ -> false)                      

(define maxinfexceeded?
  -> (> (inferences) (value *maxinferences*)))

(define errormaxinfs
  -> (simple-error "maximum inferences exceeded~%"))
  
(defprolog udefs*
   P Hyp (mode [D | _] -) <-- (call [D P Hyp]);
   P Hyp (mode [_ | Ds] -) <-- (udefs* P Hyp Ds);)   
                                                     
(defprolog th*
  X A Hyps <-- (show [X : A] Hyps) (when false);
  X A _ <-- (fwhen (typedf? X)) (bind F (sigf X)) (call [F A]);
  X A _ <-- (base X A);
  X A Hyp <-- (by_hypothesis X A Hyp);
  (mode [F] -) A Hyp <-- (th* F [--> A] Hyp);
  (mode [F X] -) A Hyp <-- (th* F [B --> A] Hyp) (th* X B Hyp);
  (mode [cons X Y] -) [list A] Hyp <-- (th* X A Hyp) (th* Y [list A] Hyp);
  (mode [@p X Y] -) [A * B] Hyp <-- (th* X A Hyp) (th* Y B Hyp);
  (mode [@v X Y] -) [vector A] Hyp <-- (th* X A Hyp) (th* Y [vector A] Hyp);
  (mode [@s X Y] -) string Hyp <-- (th* X string Hyp) (th* Y string Hyp);
  (mode [lambda X Y] -) [A --> B] Hyp <-- ! 
                                           (bind X&& (placeholder)) 
                                           (bind Z (ebr X&& X Y))
                                           (th* Z B [[X&& : A] | Hyp]); 
  (mode [let X Y Z] -) A Hyp <-- ! (th* Y B Hyp) 
                                    (bind X&& (placeholder))
                                    (bind W (ebr X&& X Z))
                                    (th* W A [[X&& : B] | Hyp]);
  (mode [open FileName Direction] -) [stream Direction] Hyp <-- ! (th* FileName string Hyp);
  (mode [type X A] -) B Hyp <-- ! (unify A B) (th* X A Hyp);
  (mode [input+ A Stream] -) B Hyp <-- (bind C (demodulate A)) (unify B C) (th* Stream [stream in] Hyp);
  (mode [read+ : A Stream] -) B Hyp <-- (bind C (demodulate A)) (unify B C) (th* Stream [stream in] Hyp);
  (mode [set Var Val] -) A Hyp <-- ! (th* Var symbol Hyp) ! (th* [value Var] A Hyp) (th* Val A Hyp);
  (mode [<-sem F] -) C Hyp <-- ! 
                               (th* F [A ==> B] Hyp)
                               !
                               (bind F&& (concat && F))
                               !
                               (th* F&& C [[F&& : B] | Hyp]);
  (mode [fail] -) symbol _ <--;
   X A Hyp <-- (t*-hyps Hyp NewHyp) (th* X A NewHyp);
  (mode [define F | X] -) A Hyp <-- ! (t*-def [define F | X] A Hyp);
  (mode [defcc F | X] -) A Hyp <-- ! (t*-defcc [defcc F | X] A Hyp);
  (mode [defmacro | _] -) unit Hyp <-- !;
  (mode [process-datatype | _] -) symbol _ <--;
  (mode [synonyms-help | _] -) symbol _ <--;
  X A Hyp <-- (bind Datatypes (value *datatypes*))  
              (udefs* [X : A] Hyp Datatypes);)

(defprolog t*-hyps
    (mode [[[cons X Y] : (mode [list A] +)] | Hyp] -) Out 
    <-- (bind Out [[X : A] [Y : [list A]] | Hyp]);
    (mode [[[@p X Y] : (mode [A * B] +)] | Hyp] -) Out 
    <-- (bind Out [[X : A] [Y : B] | Hyp]);
    (mode [[[@v X Y] : (mode [vector A] +)] | Hyp] -) Out 
    <-- (bind Out [[X : A] [Y : [vector A]] | Hyp]); 
    (mode [[[@s X Y] : (mode string +)] | Hyp] -) Out 
    <-- (bind Out [[X : string] [Y : string] | Hyp]);
    (mode [X | Hyp] -) Out <-- (bind Out [X | NewHyps]) (t*-hyps Hyp NewHyps);) 
             
(define show
  P Hyps ProcessN Continuation 
   -> (do (line)
          (show-p (deref P ProcessN))
          (nl)
          (nl)
          (show-assumptions (deref Hyps ProcessN) 1)
          (output "~%> ") 
          (pause-for-user)
          (thaw Continuation))   where (value *spy*)
   _ _ _ Continuation -> (thaw Continuation))

(define line
  -> (let Infs (inferences)
       (output "____________________________________________________________ ~A inference~A ~%?- " 
                Infs (if (= 1 Infs) "" "s"))))
                             
(define show-p 
  [X : A] -> (output "~R : ~R" X A)
  P -> (output "~R" P))
 
\* Enumerate assumptions. *\
(define show-assumptions
  [] _ -> skip
  [X | Y] N -> (do (output "~A. " N) (show-p X) (nl) (show-assumptions Y (+ N 1))))
  
\* Pauses for user *\
(define pause-for-user
   -> (let Byte (read-byte (stinput)) 
             (if (= Byte 94) 
                 (error "input aborted~%") 
                 (nl)))) 

\* Does the function have a type? *\
(define typedf?
   F -> (cons? (assoc F (value *signedfuncs*))))

\* The name of the Horn clause containing the signature of F. *\
(define sigf 
  F -> (concat type-signature-of- F))  
                                                     
\* Generate a placeholder - a symbol which stands for an arbitrary object.  *\    
(define placeholder
  -> (gensym &&))                                                          

(defprolog base
  X number <-- (fwhen (number? X));
  X boolean <-- (fwhen (boolean? X));
  X string <-- (fwhen (string? X));
  X symbol <-- (fwhen (symbol? X)) (fwhen (not (ue? X)));
  (mode [] -) [list A] <--;)   

(defprolog by_hypothesis
 X A (mode [[Y : B] | _] -) <-- (identical X Y) (unify! A B);
 X A (mode [_ | Hyp] -) <-- (by_hypothesis X A Hyp);)                 

(defprolog t*-def
  (mode [define F | X] -) A Hyp <-- (t*-defh (compile (function <sig+rules>) X) F A Hyp);)

(defprolog t*-defh
  (mode [Sig | Rules] -) F A Hyp <-- (t*-defhh Sig (ue Sig) F A Hyp Rules);)

(defprolog t*-defhh 
  Sig Sig&& F A Hyp Rules <-- (t*-rules Rules Sig&& 1 F [[F : Sig&&] | Hyp])
                              (memo F Sig A);)

(defprolog memo
  F A A <-- (bind Jnk (declare F A));)  

(defcc <sig+rules>
  <signature> <rules> := [<signature> | <rules>];)

(define ue
  [P X] -> [P X]	where (= P protect)
  [X | Y] -> (map (function ue) [X | Y])  
  X -> (concat && X)        where (variable? X)
  X -> X)

(define ues
  X -> [X]   where (ue? X)
  [X | Y] -> (union (ues X) (ues Y))
  _ -> [])

(define ue?
  X -> (and (symbol? X) (ue-h? (str X))))

(define ue-h?
  (@s "&&" _) -> true
  _ -> false)

(defprolog t*-rules
  (mode [] -) _ _ _ _ <--;
  (mode [[[] Action] | Rules] -) (mode [--> A] -) N F Hyp 
  <-- (t*-rule [[] (ue Action)] A Hyp) 
      !
      (t*-rules Rules A (+ N 1) F Hyp);
  (mode [Rule | Rules] -) A N F Hyp <-- (t*-rule (ue Rule) A Hyp) 
                                        !
                                        (t*-rules Rules A (+ N 1) F Hyp);
  _ _ N F _ <-- (bind Err (error "type error in rule ~A of ~A" N F));)
    
(defprolog t*-rule
  (mode [[] Action] -) A Hyp <-- ! (t*-action (curry Action) A Hyp);
  (mode [[Pattern | Patterns] Action] -) (mode [A --> B] -) Hyp 
   <-- (t*-pattern Pattern A)
       !
       (t*-rule [Patterns Action] B [[Pattern : A] | Hyp]);) 

(defprolog t*-action
  (mode [where P Action] -) A Hyp 
  <-- ! (t* [P : boolean] Hyp) ! (t*-action Action A [[P : verified] | Hyp]);
  (mode [choicepoint! [[fail-if F] Action]] -) A Hyp 
  <-- ! (t*-action [where [not [F Action]] Action] A Hyp);
  (mode [choicepoint! Action] -) A Hyp 
  <-- ! (t*-action [where [not [[= Action] [fail]]] Action] A Hyp);
  Action A Hyp <-- (t* [Action : A] Hyp);) 

(defprolog t*-pattern
  Pattern A <-- (tms->hyp (ues Pattern) Hyp) ! (t* [Pattern : A] Hyp);)

(defprolog tms->hyp
  (mode [] -) [] <--;
  (mode [Tm | Tms] -) [[Tm : A] | Hyp] <-- (tms->hyp Tms Hyp);)   

(defprolog findall
  Pattern Literal X <-- (bind A (gensym a)) 
                        (bind B (set A [])) 
                        (findallhelp Pattern Literal X A);)
  
(defprolog findallhelp
  Pattern Literal X A <-- (call Literal) (remember A Pattern) (when false);
  _ _ X A <-- (bind X (value A));)

(defprolog remember
  A Pattern <-- (is B (set A [Pattern | (value A)]));)   

(defprolog t*-defcc
 (mode [defcc F { [list A] ==> B } | Rest] -) C Hyp 
    <-- (bind Sig (ue [[list A] ==> B]))
        (bind ListA&& (hd Sig))
        (bind B&& (hd (tl (tl Sig))))
        (bind Rest& (plug-wildcards Rest))
        (bind Rest&& (ue Rest&))
        (get-rules Rules Rest&&)
        !
        (tc-rules F Rules ListA&& B&& [[F : Sig] | Hyp] 1)
        (unify C [[list A] ==> B])
        (bind Declare (declare F [[list A] ==> B]));)

(define plug-wildcards
  [X | Y] -> (map (function plug-wildcards) [X | Y])
  X -> (gensym (intern "X"))   where (= X _)
  X -> X)
                                       
(defprolog get-rules 
  [] (mode [] -) <-- !;
  [Rule | Rules] Rest <-- (first-rule Rest Rule Other) 
                          !
                          (get-rules Rules Other);)
                          
(defprolog first-rule
  (mode [; | Other] -) [] Other <-- !;
  (mode [X | Rest] -) [X | Rule] Other <-- (first-rule Rest Rule Other);)
                                           
(defprolog tc-rules
  _ (mode [] -) _ _ _ _ <--;
  F (mode [Rule | Rules] -) (mode [list A] -) B Hyps N
  <-- (tc-rule F Rule A B Hyps N)
      (is M (+ N 1))
      !
      (tc-rules F Rules [list A] B Hyps M);)

(defprolog tc-rule
  _ Rule A B Hyps _ <-- (check-defcc-rule Rule A B Hyps);
  F _ _ _ _ N <-- (bind Err (error "type error in rule ~A of ~A" N F));)

(defprolog check-defcc-rule 
  Rule A B Hyps <--
      (get-syntax+semantics Syntax Semantics Rule)
      !
      (syntax-hyps Syntax Hyps SynHyps A) 
      !
      (syntax-check Syntax A SynHyps)
      !
      (semantics-check Semantics B SynHyps);)
      
(defprolog syntax-hyps
  (mode [] -) SynHyps SynHyps A <--;
  (mode [X | Y] -) Hyps [[X : A] | SynHyps] A <-- (when (ue? X))
                                                  !
                                                  (syntax-hyps Y Hyps SynHyps A);
  (mode [_ | Y] -) Hyps SynHyps A <-- (syntax-hyps Y Hyps SynHyps A);)  
                                     
(defprolog get-syntax+semantics
  [] S (mode [:= Semantics] -) <-- ! (bind S Semantics);
  [] S (mode [:= Semantics where G] -) <-- ! (bind S [where G Semantics]);
  [X | Syntax] Semantics (mode [X | Rule] -)
   <-- (get-syntax+semantics Syntax Semantics Rule);)
                                     
(defprolog syntax-check
  (mode [] -) _ _ <--;
  (mode [X | Syntax] -) A Hyps <-- (fwhen (grammar_symbol? X))
                                   !
                                   (t* [X : [[list B] ==> C]] Hyps)
                                   !
                                   (bind X&& (concat && X))
                                   !
                                   (t* [X&& : [list A]] [[X&& : [list B]] | Hyps]) 
                                   !
                                   (syntax-check Syntax A Hyps);
  (mode [X | Syntax] -) A Hyps <-- (t* [X : A] Hyps)
                                   !
                                   (syntax-check Syntax A Hyps);)
  
(defprolog semantics-check
  Semantics B Hyps <-- (is Semantics* (curry (rename-semantics Semantics)))
                       (t* [Semantics* : B] Hyps);)
                       
(define rename-semantics
  [X | Y] -> [(rename-semantics X) | (rename-semantics Y)]
  X -> [<-sem X]  where (grammar_symbol? X)
  X -> X)   
  )
         
         