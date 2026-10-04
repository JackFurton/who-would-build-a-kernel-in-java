package duke.conformance;

final class TypeChecks {

    interface Named {
    }

    interface Labeled extends Named {
    }

    static class Animal {
    }

    static class Dog extends Animal implements Labeled {
    }

    static final class Puppy extends Dog {
    }

    static final class Rock implements Named {
    }

    static int classHierarchy() {
        Object dog = new Dog();
        Object animal = new Animal();
        int bits = 0;
        bits |= dog instanceof Animal ? 1 : 0;
        bits |= dog instanceof Dog ? 2 : 0;
        bits |= dog instanceof Puppy ? 4 : 0;
        bits |= animal instanceof Dog ? 8 : 0;
        bits |= animal instanceof Object ? 16 : 0;
        return bits;
    }

    static int nullIsNeverAnInstance() {
        Object nothing = null;
        return (nothing instanceof Object ? 1 : 0) + (nothing instanceof Dog ? 2 : 0);
    }

    static int interfaces() {
        Object puppy = new Puppy();
        Object rock = new Rock();
        Object animal = new Animal();
        int bits = 0;
        bits |= puppy instanceof Labeled ? 1 : 0;
        bits |= puppy instanceof Named ? 2 : 0;
        bits |= rock instanceof Named ? 4 : 0;
        bits |= rock instanceof Labeled ? 8 : 0;
        bits |= animal instanceof Named ? 16 : 0;
        return bits;
    }

    static int arrays() {
        Object ints = new int[1];
        Object strings = new String[1];
        Object dogs = new Dog[1];
        Object grid = new int[1][1];
        int bits = 0;
        bits |= ints instanceof int[] ? 1 : 0;
        bits |= ints instanceof long[] ? 2 : 0;
        bits |= ints instanceof Object[] ? 4 : 0;
        bits |= strings instanceof Object[] ? 8 : 0;
        bits |= dogs instanceof Animal[] ? 16 : 0;
        bits |= dogs instanceof Named[] ? 32 : 0;
        bits |= dogs instanceof Puppy[] ? 64 : 0;
        bits |= grid instanceof Object[] ? 128 : 0;
        bits |= grid instanceof int[][] ? 256 : 0;
        bits |= strings instanceof Object ? 512 : 0;
        return bits;
    }

    static int successfulCasts() {
        Object s = "cast me";
        Object puppy = new Puppy();
        Object nothing = null;
        String str = (String) s;
        Dog dog = (Dog) puppy;
        Named named = (Named) puppy;
        Dog none = (Dog) nothing;
        return str.length() + (dog == puppy ? 100 : 0) + (named == puppy ? 1000 : 0) + (none == null ? 10000 : 0);
    }

    static int patternMatching() {
        Object[] things = {new Puppy(), "text", new Rock(), null};
        int total = 0;
        for (Object o : things) {
            if (o instanceof String s) {
                total += s.length();
            } else if (o instanceof Dog) {
                total += 100;
            } else if (o instanceof Named) {
                total += 1000;
            }
        }
        return total;
    }

    static int covariantArrayStores() {
        Animal[] animals = new Dog[2];
        animals[0] = new Puppy();
        animals[1] = null;
        Object[] objects = new Named[2];
        objects[0] = new Rock();
        objects[1] = new Dog();
        return (animals[0] instanceof Puppy ? 1 : 0) + (objects[1] instanceof Dog ? 10 : 0);
    }
}
